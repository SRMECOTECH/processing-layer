package com.srm.gps.application.engine;

import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.connectivity.monitoring.StageSnapshot;
import com.srm.gps.connectivity.redis.EngineMetricsRedisMirror;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Two small background jobs:
 *  - every 5 s: copy the load counters to Redis (browse them in RedisInsight under gps:engine:)
 *  - every 30 s: write one ENGINE line per active producer/consumer to the log (logger "gps.engine")
 *
 * Filter the summary:  docker compose logs gps-service | Select-String "ENGINE"
 */
@Component
public class EngineReportingJobs {

    private static final Logger ENGINE_LOG = LoggerFactory.getLogger("gps.engine");

    private final PipelineMetrics metrics;
    private final EngineMetricsRedisMirror redisMirror;
    private volatile boolean redisWarningLogged;

    public EngineReportingJobs(PipelineMetrics metrics, EngineMetricsRedisMirror redisMirror) {
        this.metrics = metrics;
        this.redisMirror = redisMirror;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void copyLoadCountersToRedis() {
        try {
            for (PipelineStage stage : PipelineStage.values()) {
                redisMirror.write(metrics.snapshot(stage));
            }
            redisWarningLogged = false;
        } catch (RuntimeException redisProblem) {
            if (!redisWarningLogged) {
                ENGINE_LOG.warn("ENGINE | could not copy load counters to Redis: {}", redisProblem.getMessage());
                redisWarningLogged = true;
            }
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void logLoadSummary() {
        for (PipelineStage stage : PipelineStage.values()) {
            StageSnapshot snapshot = metrics.snapshot(stage);
            if (snapshot.totalMessages() == 0 && snapshot.failures() == 0) {
                continue;
            }
            ENGINE_LOG.info(String.format(Locale.ROOT,
                    "ENGINE | %-27s | %7.1f msg/s | total %9d | failures %4d | devices %4d | per partition %s",
                    stage.shortName(), snapshot.messagesPerSecond(), snapshot.totalMessages(), snapshot.failures(),
                    snapshot.messagesByImei().size(), snapshot.messagesByPartition()));
        }
    }
}
