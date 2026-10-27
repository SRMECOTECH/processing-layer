package com.srm.gps.connectivity.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * gps.kafka.* in application.yml: how long and how safely Kafka keeps the platform's topics.
 *
 * @param retention          how long every platform topic keeps its records (default 31 days: a full calendar month)
 * @param replicationFactor  copies of every partition on different brokers (default 1: one local broker; 3 in HA)
 * @param minInsyncReplicas  copies that must have a record before the producer gets its "ok" with acks=all
 *                           (default 1; 2 in HA, so one broker can fail without losing an acknowledged record)
 */
@ConfigurationProperties("gps.kafka")
public record KafkaDurabilityProperties(Duration retention, Integer replicationFactor, Integer minInsyncReplicas) {

    public static final Duration ONE_MONTH = Duration.ofDays(31);

    public Duration retentionOrDefault() {
        return retention != null ? retention : ONE_MONTH;
    }

    public int replicationFactorOrDefault() {
        return replicationFactor != null ? replicationFactor : 1;
    }

    public int minInsyncReplicasOrDefault() {
        return minInsyncReplicas != null ? minInsyncReplicas : 1;
    }
}
