package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the platform's Kafka topics. Spring creates any that do not exist yet when the service starts,
 * so nobody has to create topics by hand.
 */
@Configuration
@OnlyInKafkaFlow
public class KafkaTopicDeclarations {

    /** One broker locally, so one copy of each message. Raise this on a real cluster. */
    private static final int REPLICAS = 1;

    @Bean
    public NewTopic rawDeviceFramesTopic() {
        return TopicBuilder.name(KafkaTopicNames.RAW_DEVICE_FRAMES)
                .partitions(KafkaTopicNames.PARTITIONS_PER_TOPIC).replicas(REPLICAS).build();
    }

    @Bean
    public NewTopic processedPositionsTopic() {
        return TopicBuilder.name(KafkaTopicNames.PROCESSED_POSITIONS)
                .partitions(KafkaTopicNames.PARTITIONS_PER_TOPIC).replicas(REPLICAS).build();
    }

    @Bean
    public NewTopic rejectedFramesTopic() {
        return TopicBuilder.name(KafkaTopicNames.REJECTED_FRAMES)
                .partitions(KafkaTopicNames.PARTITIONS_PER_TOPIC).replicas(REPLICAS).build();
    }
}
