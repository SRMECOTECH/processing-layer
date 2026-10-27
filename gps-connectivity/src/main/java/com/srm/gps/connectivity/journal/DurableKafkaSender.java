package com.srm.gps.connectivity.journal;

import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The producer for records nobody else keeps a copy of (device frames, vehicle events): journal first, then Kafka.
 *
 *   send()  ->  IngestJournal.append (on disk)  ->  Kafka  ->  acknowledged: removed from the journal
 *                                                          ->  failed: stays; re-sent by drain() until Kafka has it
 *
 * Order: while anything is unacknowledged and not on its way (Kafka was down, the service restarted), new records are
 * only journaled, and drain() sends the journal in sequence order, one batch at a time - a device's frames reach
 * Kafka in the order they arrived even across an outage.
 *
 * A caller never waits for Kafka, only for the disk; it gets an exception only if the journal itself cannot write
 * (disk full): then nothing was accepted and the caller knows it - a frame is never dropped silently.
 */
@Component
@OnlyInKafkaFlow
public class DurableKafkaSender {

    private static final Logger LOG = LoggerFactory.getLogger(DurableKafkaSender.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    /** What the sender is doing, for the data-safety page. */
    public record Status(boolean catchingUp, int inFlight, long failedSends, String lastFailure, Instant lastFailureAt,
                         IngestJournal.Stats journal, String directory, boolean fsyncEveryRecord) {
    }

    private final IngestJournal journal;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineMetrics metrics;
    private final int drainBatchSize;
    private final Map<String, PipelineStage> stageOfTopic = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final LongAdder failedSends = new LongAdder();
    private volatile boolean catchingUp;
    private volatile long retryDelayMillis;
    private volatile long nextAttemptAtMillis;
    private volatile String lastFailure;
    private volatile Instant lastFailureAt;
    private volatile long lastWarningAtMillis;

    public DurableKafkaSender(JournalProperties settings, KafkaTemplate<String, String> kafkaTemplate, PipelineMetrics metrics) {
        this.journal = new IngestJournal(settings.directoryOrDefault(), settings.recordsPerSegmentOrDefault(),
                settings.fsyncEveryRecordOrDefault());
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.drainBatchSize = settings.drainBatchSizeOrDefault();
        for (PipelineStage stage : PipelineStage.values()) {
            if (stage.kind() == PipelineStage.Kind.PRODUCER) {
                stageOfTopic.put(stage.topic(), stage);
            }
        }
        long recovered = journal.unacknowledgedCount();
        catchingUp = recovered > 0;
        if (recovered > 0) {
            LOG.warn("Ingest journal {}: {} records were not acknowledged by Kafka before the last stop - sending them again first",
                    journal.directory().toAbsolutePath(), recovered);
        }
    }

    /** Journals the record (durable when this returns) and sends it to Kafka, now or after the journal's backlog. */
    public void send(String topic, String key, String value) {
        IngestJournal.Entry entry;
        boolean direct;
        synchronized (lock) {
            entry = journal.append(topic, key, value);
            direct = !catchingUp;
            if (direct) {
                inFlight.add(entry.sequence());
            }
        }
        if (direct) {
            dispatch(entry);
        }
    }

    /** Re-sends what the journal still holds, oldest first, one batch at a time. */
    @Scheduled(fixedDelay = 500, initialDelay = 1_000)
    public void drain() {
        drain(false);
    }

    /** @param ignoreBackOff true only in tests: do not wait for the retry delay after a failure */
    void drain(boolean ignoreBackOff) {
        if (!catchingUp || (!ignoreBackOff && System.currentTimeMillis() < nextAttemptAtMillis)) {
            return;
        }
        List<IngestJournal.Entry> batch;
        synchronized (lock) {
            if (!inFlight.isEmpty()) {
                return;   // the previous batch is still on its way
            }
            batch = journal.unacknowledged(drainBatchSize, sequence -> false);
            if (batch.isEmpty()) {
                catchingUp = false;
                retryDelayMillis = 0;
                LOG.info("Ingest journal caught up: every journaled record is in Kafka");
                return;
            }
            batch.forEach(entry -> inFlight.add(entry.sequence()));
        }
        for (int i = 0; i < batch.size(); i++) {
            if (!dispatch(batch.get(i))) {
                // Kafka refused at once (no metadata: it is down): do not wait max.block.ms for every other record
                batch.subList(i + 1, batch.size()).forEach(entry -> inFlight.remove(entry.sequence()));
                break;
            }
        }
    }

    @Scheduled(fixedDelay = 1_000, initialDelay = 1_000)
    public void checkpoint() {
        journal.checkpoint();
    }

    public Status status() {
        return new Status(catchingUp, inFlight.size(), failedSends.sum(), lastFailure, lastFailureAt, journal.stats(),
                journal.directory().toAbsolutePath().toString(), journal.fsyncEveryRecord());
    }

    @PreDestroy
    public void close() {
        kafkaTemplate.flush();
        journal.close();
    }

    /** @return false when the send was refused immediately */
    private boolean dispatch(IngestJournal.Entry entry) {
        try {
            kafkaTemplate.send(entry.topic(), entry.key(), entry.value())
                    .whenComplete((sent, failure) -> {
                        if (failure == null) {
                            acknowledged(entry, sent);
                        } else {
                            failed(entry, failure);
                        }
                    });
            return true;
        } catch (RuntimeException refused) {
            failed(entry, refused);
            return false;
        }
    }

    private void acknowledged(IngestJournal.Entry entry, SendResult<String, String> sent) {
        journal.acknowledge(entry.sequence());
        inFlight.remove(entry.sequence());
        retryDelayMillis = 0;
        PipelineStage stage = stageOfTopic.get(entry.topic());
        if (stage != null) {
            metrics.recordMessage(stage, entry.key(), sent.getRecordMetadata().partition());
        }
    }

    private void failed(IngestJournal.Entry entry, Throwable failure) {
        inFlight.remove(entry.sequence());
        catchingUp = true;
        failedSends.increment();
        lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        lastFailureAt = Instant.now();
        retryDelayMillis = Math.min(MAX_RETRY_DELAY.toMillis(), Math.max(1_000, retryDelayMillis * 2));
        nextAttemptAtMillis = System.currentTimeMillis() + retryDelayMillis;
        PipelineStage stage = stageOfTopic.get(entry.topic());
        if (stage != null) {
            metrics.recordFailure(stage);
        }
        if (System.currentTimeMillis() - lastWarningAtMillis > 60_000) {
            lastWarningAtMillis = System.currentTimeMillis();
            LOG.warn("Kafka did not take a journaled record ({}); it stays in the journal and is sent again: {}",
                    entry.topic(), lastFailure);
        }
    }
}
