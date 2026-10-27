package com.srm.gps.application.safety;

import com.srm.gps.application.safety.DataSafetyChecks.BrokerFacts;
import com.srm.gps.application.safety.DataSafetyChecks.Check;
import com.srm.gps.application.safety.DataSafetyChecks.Facts;
import com.srm.gps.application.safety.DataSafetyChecks.GroupFacts;
import com.srm.gps.application.safety.DataSafetyChecks.JournalFacts;
import com.srm.gps.application.safety.DataSafetyChecks.ProducerFacts;
import com.srm.gps.application.safety.DataSafetyChecks.Status;
import com.srm.gps.application.safety.DataSafetyChecks.TopicFacts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The data-safety verdict: FAIL only when data was or can be lost, WARN when a protection is weaker than it could be. */
class DataSafetyChecksTest {

    private static final Duration MONTH = Duration.ofDays(31);
    private static final long MONTH_MS = MONTH.toMillis();

    @Test
    void theSingleBrokerSetupLosesNothingButSaysItIsOneCopy() {
        List<Check> checks = DataSafetyChecks.evaluate(singleBroker(List.of(group("gps-position-storage-service", 3, 0, 0)), 0));

        assertEquals(Status.WARN, DataSafetyChecks.verdict(checks), "only the single copy is a warning");
        assertEquals(Status.OK, status(checks, "retention"));
        assertEquals(Status.OK, status(checks, "no-loss"));
        assertEquals(Status.WARN, status(checks, "replication"));
        assertEquals(Status.OK, status(checks, "broker-fsync"), "flushed before every ack");
        assertEquals(Status.OK, status(checks, "offsets"));
        assertEquals(Status.OK, status(checks, "producers"));
        assertEquals(Status.OK, status(checks, "journal"));
    }

    @Test
    void threeBrokersWithTwoInSyncPassEveryCheck() {
        Facts facts = new Facts(MONTH, List.of(topic("gps.raw-device-frames", 3, 2, MONTH_MS), topic("gps.processed-positions", 3, 2, MONTH_MS)),
                List.of(group("gps-trip-service", 3, 12, 0)), new BrokerFacts(3, Long.MAX_VALUE, 44_640L, false),
                journal(false, 4), new ProducerFacts("all", true), 0);

        assertEquals(Status.OK, DataSafetyChecks.verdict(DataSafetyChecks.evaluate(facts)));
    }

    @Test
    void aGroupWhoseUnreadRecordsWereDeletedIsAFailure() {
        List<Check> checks = DataSafetyChecks.evaluate(singleBroker(List.of(group("gps-trip-service", 0, 0, 1_250)), 0));

        assertEquals(Status.FAIL, status(checks, "no-loss"));
        assertTrue(detail(checks, "no-loss").contains("gps-trip-service lost 1250"));
        assertEquals(Status.FAIL, DataSafetyChecks.verdict(checks));
    }

    @Test
    void aGroupThatCannotBeAskedDuringAFailoverIsAWarningNotAFalseAlarm() {
        List<Check> checks = DataSafetyChecks.evaluate(singleBroker(List.of(group("gps-trip-service", 3, 0, 0),
                new GroupFacts("gps-ignition-service", 0, -1, 0, false)), 0));

        assertEquals(Status.WARN, status(checks, "no-loss"));
        assertTrue(detail(checks, "no-loss").contains("gps-ignition-service"));
        assertEquals(Status.OK, status(checks, "stopped-groups"), "not reported as stopped: it was just not reachable");
    }

    @Test
    void aTopicKeptLessThanAMonthOrLimitedBySizeIsAFailure() {
        Facts week = new Facts(MONTH, List.of(topic("gps.raw-device-frames", 1, 1, Duration.ofDays(7).toMillis())), List.of(),
                broker(), journal(false, 0), new ProducerFacts("all", true), 0);
        Facts bySize = new Facts(MONTH, List.of(new TopicFacts("gps.raw-device-frames", 1, 1, MONTH_MS, 1_000_000L, "delete")), List.of(),
                broker(), journal(false, 0), new ProducerFacts("all", true), 0);

        assertEquals(Status.FAIL, status(DataSafetyChecks.evaluate(week), "retention"));
        assertTrue(detail(DataSafetyChecks.evaluate(week), "retention").contains("7 days"));
        assertEquals(Status.FAIL, status(DataSafetyChecks.evaluate(bySize), "retention"));
    }

    @Test
    void aProducerThatDoesNotWaitForEveryCopyIsAFailure() {
        Facts facts = new Facts(MONTH, List.of(topic("gps.raw-device-frames", 1, 1, MONTH_MS)), List.of(), broker(), journal(false, 0),
                new ProducerFacts("1", true), 0);

        assertEquals(Status.FAIL, status(DataSafetyChecks.evaluate(facts), "producers"));
    }

    @Test
    void framesWaitingInTheJournalParkedRecordsAndStoppedGroupsAreWarningsNotLosses() {
        Facts facts = new Facts(MONTH, List.of(topic("gps.raw-device-frames", 1, 1, MONTH_MS)),
                List.of(group("gps-ignition-service", 0, 4_000, 0)), broker(), journal(true, 9_000), new ProducerFacts("all", true), 3);
        List<Check> checks = DataSafetyChecks.evaluate(facts);

        assertEquals(Status.WARN, status(checks, "journal"));
        assertEquals(Status.WARN, status(checks, "dead-letters"));
        assertEquals(Status.WARN, status(checks, "stopped-groups"));
        assertEquals(Status.WARN, DataSafetyChecks.verdict(checks));
    }

    @Test
    void aBrokerThatAcknowledgesBeforeItsOnlyCopyIsOnDiskIsWarned() {
        Facts facts = new Facts(MONTH, List.of(topic("gps.raw-device-frames", 1, 1, MONTH_MS)), List.of(),
                new BrokerFacts(1, Long.MAX_VALUE, 10_080L, true), journal(false, 0), new ProducerFacts("all", true), 0);
        List<Check> checks = DataSafetyChecks.evaluate(facts);

        assertEquals(Status.WARN, status(checks, "broker-fsync"));
        assertEquals(Status.WARN, status(checks, "offsets"), "7 days of offsets for a month of data");
        assertEquals(Status.WARN, status(checks, "auto-create"));
    }

    private static Facts singleBroker(List<GroupFacts> groups, long parked) {
        return new Facts(MONTH, List.of(topic("gps.raw-device-frames", 1, 1, MONTH_MS), topic("gps.dead-letters", 1, 1, MONTH_MS)),
                groups, broker(), journal(false, 2), new ProducerFacts("all", true), parked);
    }

    private static BrokerFacts broker() {
        return new BrokerFacts(1, 1L, 44_640L, false);
    }

    private static TopicFacts topic(String name, int replicas, int minInsync, long retentionMs) {
        return new TopicFacts(name, replicas, minInsync, retentionMs, -1L, "delete");
    }

    private static GroupFacts group(String name, int members, long lag, long lost) {
        return new GroupFacts(name, members, lag, lost, true);
    }

    private static JournalFacts journal(boolean catchingUp, long unacknowledged) {
        return new JournalFacts(catchingUp, unacknowledged, catchingUp ? Instant.now().minusSeconds(600) : null, true);
    }

    private static Status status(List<Check> checks, String id) {
        return checks.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().status();
    }

    private static String detail(List<Check> checks, String id) {
        return checks.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().detail();
    }
}
