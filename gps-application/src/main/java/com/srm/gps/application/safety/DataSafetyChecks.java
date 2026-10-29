package com.srm.gps.application.safety;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The data-safety verdict, as plain rules over plain facts (so every rule is unit-tested). Each check answers one
 * question an operator would ask before trusting "nothing is lost":
 *
 *   retention      does Kafka keep every platform topic for at least a month?
 *   no-loss        did any consumer group fall so far behind that Kafka deleted records it had not read?
 *   replication    how many copies of each record exist (and must exist before the producer hears "ok")?
 *   broker-fsync   with a single copy, is it on disk before the "ok"?
 *   offsets        does a stopped consumer keep its place for as long as the data is kept?
 *   auto-create    can a typo create a 1-partition topic that nobody reads?
 *   producers      do producers wait for every in-sync copy, without duplicates on retry?
 *   journal        has every frame and event reached Kafka (and is the journal fsynced)?
 *   dead-letters   are there parked records waiting for a fix and a replay?
 *   stopped-groups is any consumer group not running while records wait for it?
 *
 * FAIL = data was lost or can be lost now. WARN = nothing is lost, but a protection is weaker than it could be.
 */
public final class DataSafetyChecks {

    public enum Status { OK, WARN, FAIL }

    public record Check(String id, String title, Status status, String detail) {
    }

    public record TopicFacts(String topic, int replicationFactor, Integer minInsyncReplicas, Long retentionMs,
                             Long retentionBytes, String cleanupPolicy) {
    }

    /**
     * @param lostRecords records Kafka deleted before this group read them (committed offset below the log start)
     * @param answered    false when the group's coordinator did not answer in time (a broker failing over)
     */
    public record GroupFacts(String group, int members, long lag, long lostRecords, boolean answered) {
    }

    public record BrokerFacts(int brokers, Long flushIntervalMessages, Long offsetsRetentionMinutes, Boolean autoCreateTopics) {
    }

    public record JournalFacts(boolean catchingUp, long unacknowledged, Instant oldestUnacknowledgedAt, boolean fsyncEveryRecord) {
    }

    public record ProducerFacts(String acks, Boolean idempotence) {
    }

    public record Facts(Duration retentionTarget, List<TopicFacts> topics, List<GroupFacts> groups, BrokerFacts broker,
                        JournalFacts journal, ProducerFacts producer, long parkedDeadLetters) {
    }

    private DataSafetyChecks() {
    }

    public static List<Check> evaluate(Facts facts) {
        List<Check> checks = new ArrayList<>();
        checks.add(retention(facts));
        checks.add(noLoss(facts.groups()));
        checks.add(replication(facts.topics()));
        checks.add(brokerFsync(facts.topics(), facts.broker()));
        checks.add(offsets(facts));
        checks.add(autoCreate(facts.broker()));
        checks.add(producers(facts.producer()));
        checks.add(journal(facts.journal()));
        checks.add(deadLetters(facts.parkedDeadLetters()));
        checks.add(stoppedGroups(facts.groups()));
        return checks;
    }

    public static Status verdict(List<Check> checks) {
        if (checks.stream().anyMatch(c -> c.status() == Status.FAIL)) {
            return Status.FAIL;
        }
        return checks.stream().anyMatch(c -> c.status() == Status.WARN) ? Status.WARN : Status.OK;
    }

    static Check retention(Facts facts) {
        long target = facts.retentionTarget().toMillis();
        List<String> short_ = facts.topics().stream()
                .filter(t -> t.retentionMs() == null || (t.retentionMs() >= 0 && t.retentionMs() < target)
                        || (t.retentionBytes() != null && t.retentionBytes() >= 0)
                        || (t.cleanupPolicy() != null && !t.cleanupPolicy().contains("delete")))
                .map(t -> t.topic() + " (" + days(t.retentionMs()) + ")")
                .toList();
        return short_.isEmpty()
                ? new Check("retention", "A month of data in Kafka", Status.OK,
                "Every platform topic keeps its records for " + days(target) + ", deleted by age only, never by size.")
                : new Check("retention", "A month of data in Kafka", Status.FAIL,
                "Kept for less than " + days(target) + " (or limited by size): " + String.join(", ", short_)
                        + ". The service sets this at start-up (spring.kafka.admin.modify-topic-configs).");
    }

    static Check noLoss(List<GroupFacts> groups) {
        List<String> lost = groups.stream().filter(g -> g.lostRecords() > 0)
                .map(g -> g.group() + " lost " + g.lostRecords()).toList();
        List<String> silent = groups.stream().filter(g -> !g.answered()).map(GroupFacts::group).toList();
        if (lost.isEmpty() && !silent.isEmpty()) {
            return new Check("no-loss", "No consumer fell behind retention", Status.WARN,
                    "Could not check " + String.join(", ", silent) + " right now (its coordinator did not answer - a broker failing over?). Every group that answered is inside what Kafka keeps.");
        }
        return lost.isEmpty()
                ? new Check("no-loss", "No consumer fell behind retention", Status.OK,
                "Every consumer group's position is inside what Kafka still keeps: nothing was deleted before it was read.")
                : new Check("no-loss", "No consumer fell behind retention", Status.FAIL,
                "Kafka deleted records these groups had not read: " + String.join(", ", lost)
                        + ". Recover them from the producer side (journal, smart_truck replay) or PostgreSQL.");
    }

    static Check replication(List<TopicFacts> topics) {
        int copies = topics.stream().mapToInt(TopicFacts::replicationFactor).min().orElse(0);
        int inSync = topics.stream().mapToInt(t -> t.minInsyncReplicas() == null ? 1 : t.minInsyncReplicas()).min().orElse(0);
        if (copies >= 3 && inSync >= 2) {
            return new Check("replication", "Copies on different brokers", Status.OK,
                    copies + " copies of every record, " + inSync + " of them written before the producer hears ok: a broker can fail without losing anything.");
        }
        if (copies >= 2 && inSync >= 2) {
            return new Check("replication", "Copies on different brokers", Status.WARN,
                    copies + " copies, " + inSync + " in sync: safe against one failure, but a second failure stops writes. Use 3 copies.");
        }
        return new Check("replication", "Copies on different brokers", Status.WARN,
                copies + " cop" + (copies == 1 ? "y" : "ies") + " of every record, " + inSync + " in sync before ok: a lost disk loses data."
                        + " Run the 3-broker setup (docker-compose.ha.yml) for replication 3 / min in-sync 2.");
    }

    static Check brokerFsync(List<TopicFacts> topics, BrokerFacts broker) {
        int copies = topics.stream().mapToInt(TopicFacts::replicationFactor).min().orElse(0);
        if (copies >= 3) {
            return new Check("broker-fsync", "Acknowledged means durable", Status.OK,
                    "Replicated: an acknowledged record is on " + copies + " brokers, so it does not depend on one disk flush.");
        }
        Long flush = broker == null ? null : broker.flushIntervalMessages();
        return flush != null && flush == 1
                ? new Check("broker-fsync", "Acknowledged means durable", Status.OK,
                "Single copy, flushed to disk before every acknowledgement (log.flush.interval.messages=1): a power cut loses nothing acknowledged.")
                : new Check("broker-fsync", "Acknowledged means durable", Status.WARN,
                "Single copy and the broker acknowledges before its disk flush: a power cut can lose the last seconds."
                        + " Set log.flush.interval.messages=1, or replicate.");
    }

    static Check offsets(Facts facts) {
        Long minutes = facts.broker() == null ? null : facts.broker().offsetsRetentionMinutes();
        long needed = facts.retentionTarget().toMinutes();
        return minutes != null && minutes >= needed
                ? new Check("offsets", "A stopped consumer keeps its place", Status.OK,
                "Committed offsets are kept " + days(Duration.ofMinutes(minutes).toMillis()) + ": a consumer group can be stopped as long as the data is kept and resume where it left off.")
                : new Check("offsets", "A stopped consumer keeps its place", Status.WARN,
                "Committed offsets are kept " + (minutes == null ? "an unknown time" : days(Duration.ofMinutes(minutes).toMillis()))
                        + ", less than the data: a group stopped longer restarts from the beginning (duplicates, not loss). Set offsets.retention.minutes="
                        + needed + ".");
    }

    static Check autoCreate(BrokerFacts broker) {
        return broker != null && Boolean.FALSE.equals(broker.autoCreateTopics())
                ? new Check("auto-create", "Only declared topics", Status.OK,
                "auto.create.topics.enable=false: every topic is declared with its month of retention and its partitions.")
                : new Check("auto-create", "Only declared topics", Status.WARN,
                "The broker creates topics on first use, with its own defaults (1 partition, broker retention): a misspelled topic would silently take records nobody reads.");
    }

    static Check producers(ProducerFacts producer) {
        boolean all = producer != null && ("all".equalsIgnoreCase(producer.acks()) || "-1".equals(producer.acks()));
        boolean idempotent = producer != null && !Boolean.FALSE.equals(producer.idempotence());
        return all && idempotent
                ? new Check("producers", "Producers wait for every copy", Status.OK,
                "acks=all and idempotence: a record is acknowledged only once every in-sync copy has it, and a retry never writes it twice.")
                : new Check("producers", "Producers wait for every copy", Status.FAIL,
                "Producer acks=" + (producer == null ? "?" : producer.acks()) + ", idempotence=" + (producer == null ? "?" : producer.idempotence())
                        + ": an acknowledged record can still be lost. Use acks=all with idempotence.");
    }

    static Check journal(JournalFacts journal) {
        if (journal == null) {
            return new Check("journal", "Every frame reached Kafka", Status.WARN, "The ingest journal is not running (direct flow).");
        }
        if (!journal.fsyncEveryRecord()) {
            return new Check("journal", "Every frame reached Kafka", Status.WARN,
                    "The journal does not fsync every record: a power cut can lose frames the journal had accepted. Set gps.journal.fsync-every-record=true.");
        }
        if (journal.catchingUp() || journal.unacknowledged() > 1_000) {
            return new Check("journal", "Every frame reached Kafka", Status.WARN,
                    journal.unacknowledged() + " frames and events are safe on disk in the journal, waiting for Kafka"
                            + (journal.oldestUnacknowledgedAt() == null ? "" : " since " + journal.oldestUnacknowledgedAt()) + ". They are sent in order when Kafka takes them.");
        }
        return new Check("journal", "Every frame reached Kafka", Status.OK,
                "Frames and events are fsynced to the journal before they are sent and kept until Kafka confirms them; "
                        + journal.unacknowledged() + " on their way right now.");
    }

    static Check deadLetters(long parked) {
        return parked == 0
                ? new Check("dead-letters", "Nothing parked", Status.OK, "No dead letter is waiting: every record was handled.")
                : new Check("dead-letters", "Nothing parked", Status.WARN,
                parked + " record" + (parked == 1 ? " is" : "s are") + " parked in the dead-letter archive: kept, but not handled yet. Fix the cause, then replay or discard them.");
    }

    static Check stoppedGroups(List<GroupFacts> groups) {
        List<String> waiting = groups.stream().filter(g -> g.answered() && g.members() == 0 && g.lag() > 0)
                .map(g -> g.group() + " (" + g.lag() + " waiting)").toList();
        return waiting.isEmpty()
                ? new Check("stopped-groups", "Every consumer is running", Status.OK, "No consumer group is stopped while records wait for it.")
                : new Check("stopped-groups", "Every consumer is running", Status.WARN,
                "Stopped with records waiting: " + String.join(", ", waiting) + ". They are kept for the retention period; start the group before then.");
    }

    private static String days(Long millis) {
        if (millis == null) {
            return "unknown";
        }
        if (millis < 0) {
            return "forever";
        }
        double days = millis / 86_400_000.0;
        return days == Math.rint(days) ? (long) days + " days" : String.format("%.1f days", days);
    }
}
