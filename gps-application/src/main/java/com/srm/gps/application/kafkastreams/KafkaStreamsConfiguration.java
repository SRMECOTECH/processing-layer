package com.srm.gps.application.kafkastreams;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.connectivity.flow.OnlyWithKafkaStreams;
import com.srm.gps.streams.runtime.StreamsApp;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The topics only the shadow run writes, and the health of the Kafka Streams apps in /actuator/health.
 * The apps themselves are {@link GpsStreamsRuntime}.
 */
@Configuration
@OnlyWithKafkaStreams
public class KafkaStreamsConfiguration {

    /** One broker locally, so one copy of each message (as KafkaTopicDeclarations). */
    private static final int REPLICAS = 1;

    /** Shadow mode only. Same partitions as the real topics: the parity join needs them co-partitioned. */
    @Bean
    public KafkaAdmin.NewTopics shadowTopics(KafkaStreamsProperties settings) {
        if (settings.mode() != StreamsNormalization.SHADOW) {
            return new KafkaAdmin.NewTopics();
        }
        return new KafkaAdmin.NewTopics(
                shadowTopic(KafkaTopicNames.SHADOW_PROCESSED_POSITIONS),
                shadowTopic(KafkaTopicNames.SHADOW_REJECTED_FRAMES));
    }

    private static NewTopic shadowTopic(String name) {
        return TopicBuilder.name(name).partitions(KafkaTopicNames.PARTITIONS_PER_TOPIC).replicas(REPLICAS).build();
    }

    /**
     * kafkaStreams in /actuator/health: DOWN only when an app is in ERROR (all its threads died). REBALANCING is UP -
     * it is how Kafka Streams moves tasks, and IQ answers again once it is RUNNING.
     */
    @Bean
    public HealthIndicator kafkaStreamsHealthIndicator(GpsStreamsRuntime runtime) {
        return () -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("normalization", runtime.mode().propertyValue());
            boolean failed = false;
            for (StreamsApp app : runtime.apps()) {
                details.put(app.role(), app.applicationId() + " " + app.state());
                failed |= "ERROR".equals(app.state());
            }
            return (failed ? Health.down() : Health.up()).withDetails(details).build();
        };
    }
}
