package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the platform's Kafka topics. Spring creates any that do not exist yet when the service starts, so nobody
 * has to create topics by hand - and with spring.kafka.admin.modify-topic-configs it also brings EXISTING topics in
 * line (a topic created with 7 days of retention gets 31 on the next start).
 *
 * Every topic is declared the same durable way ({@link #durable}):
 *   retention.ms               31 days (gps.kafka.retention): a full month of data stays in Kafka
 *   retention.bytes            -1: never deleted for size, only for age
 *   cleanup.policy             delete (age-based), never compacted: every record is kept, not only the last per key
 *   replication / min.insync   gps.kafka.replication-factor / min-insync-replicas (1/1 local, 3/2 in HA)
 *   unclean.leader.election    false: a broker that missed records can never become leader and drop them
 */
@Configuration
@OnlyInKafkaFlow
public class KafkaTopicDeclarations {

    private final KafkaDurabilityProperties durability;

    public KafkaTopicDeclarations(KafkaDurabilityProperties durability) {
        this.durability = durability;
    }

    /** The one way every platform topic is declared. Also used for the topics only Kafka Streams' shadow mode needs. */
    public static NewTopic durable(String name, KafkaDurabilityProperties durability) {
        return TopicBuilder.name(name)
                .partitions(KafkaTopicNames.PARTITIONS_PER_TOPIC)
                .replicas(durability.replicationFactorOrDefault())
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(durability.retentionOrDefault().toMillis()))
                .config(TopicConfig.RETENTION_BYTES_CONFIG, "-1")
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(durability.minInsyncReplicasOrDefault()))
                .config(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false")
                .build();
    }

    @Bean
    public NewTopic rawDeviceFramesTopic() {
        return durable(KafkaTopicNames.RAW_DEVICE_FRAMES, durability);
    }

    @Bean
    public NewTopic processedPositionsTopic() {
        return durable(KafkaTopicNames.PROCESSED_POSITIONS, durability);
    }

    @Bean
    public NewTopic rejectedFramesTopic() {
        return durable(KafkaTopicNames.REJECTED_FRAMES, durability);
    }

    @Bean
    public NewTopic vehicleEventsTopic() {
        return durable(KafkaTopicNames.VEHICLE_EVENTS, durability);
    }

    /**
     * Kafka Streams output. Declared here, not left to Kafka Streams: it only creates its INTERNAL topics, and the
     * broker does not auto-create topics (auto.create.topics.enable=false).
     */
    @Bean
    public NewTopic vehicleActivityTopic() {
        return durable(KafkaTopicNames.VEHICLE_ACTIVITY, durability);
    }

    /** Where records nobody could handle are parked - by the listeners and by both Kafka Streams apps. */
    @Bean
    public NewTopic deadLettersTopic() {
        return durable(KafkaTopicNames.DEAD_LETTERS, durability);
    }
}
