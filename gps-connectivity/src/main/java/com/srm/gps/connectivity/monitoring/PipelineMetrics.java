package com.srm.gps.connectivity.monitoring;

import com.srm.gps.configuration.PipelineStage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Counts every message every producer and consumer handled: in total, per second, per partition and per IMEI.
 * This is the "how much load is each part of the engine getting?" answer.
 *
 * Kept in memory (fast, no network call per message); a scheduled job copies a snapshot to Redis
 * so it can also be browsed in RedisInsight. Counters start at 0 when the service starts.
 */
@Component
public class PipelineMetrics {

    private final Map<PipelineStage, StageCounters> countersByStage = new EnumMap<>(PipelineStage.class);

    public PipelineMetrics() {
        for (PipelineStage stage : PipelineStage.values()) {
            countersByStage.put(stage, new StageCounters());
        }
    }

    /**
     * @param imei      the device the message belongs to, or null when unknown
     * @param partition the Kafka partition, or -1 in the direct flow
     */
    public void recordMessage(PipelineStage stage, String imei, int partition) {
        countersByStage.get(stage).recordMessage(imei, partition, Instant.now());
    }

    public void recordFailure(PipelineStage stage) {
        countersByStage.get(stage).recordFailure();
    }

    public StageSnapshot snapshot(PipelineStage stage) {
        return countersByStage.get(stage).snapshot(stage, Instant.now());
    }
}
