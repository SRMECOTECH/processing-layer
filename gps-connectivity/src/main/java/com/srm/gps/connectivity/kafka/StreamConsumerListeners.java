package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.IgnitionHandler;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.SpeedViolationHandler;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.StoppageHandler;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.VehicleStateHandler;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The four consumer groups after Kafka that each read the whole gps.processed-positions topic for one purpose,
 * with their own offsets: a slow weather lookup in the stoppage consumer never holds up storage or speed checks.
 */
public final class StreamConsumerListeners {

    private StreamConsumerListeners() {
    }

    @Component
    @OnlyInKafkaFlow
    public static class SpeedViolationListener extends ProcessedPositionListener {
        private final SpeedViolationHandler handler;

        public SpeedViolationListener(SpeedViolationHandler handler, PipelineMetrics metrics) {
            super(PipelineStage.SPEED_VIOLATION, metrics);
            this.handler = handler;
        }

        @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS, groupId = KafkaConsumerGroupNames.SPEED_VIOLATION,
                concurrency = KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION)
        public void onProcessedPosition(ConsumerRecord<String, String> record) {
            consume(record, handler::handle);
        }
    }

    @Component
    @OnlyInKafkaFlow
    public static class VehicleStateListener extends ProcessedPositionListener {
        private final VehicleStateHandler handler;

        public VehicleStateListener(VehicleStateHandler handler, PipelineMetrics metrics) {
            super(PipelineStage.VEHICLE_STATE, metrics);
            this.handler = handler;
        }

        @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS, groupId = KafkaConsumerGroupNames.VEHICLE_STATE,
                concurrency = KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION)
        public void onProcessedPosition(ConsumerRecord<String, String> record) {
            consume(record, handler::handle);
        }
    }

    @Component
    @OnlyInKafkaFlow
    public static class StoppageListener extends ProcessedPositionListener {
        private final StoppageHandler handler;

        public StoppageListener(StoppageHandler handler, PipelineMetrics metrics) {
            super(PipelineStage.STOPPAGE_DETECTION, metrics);
            this.handler = handler;
        }

        @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS, groupId = KafkaConsumerGroupNames.STOPPAGE,
                concurrency = KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION)
        public void onProcessedPosition(ConsumerRecord<String, String> record) {
            consume(record, handler::handle);
        }
    }

    @Component
    @OnlyInKafkaFlow
    public static class IgnitionListener extends ProcessedPositionListener {
        private final IgnitionHandler handler;

        public IgnitionListener(IgnitionHandler handler, PipelineMetrics metrics) {
            super(PipelineStage.IGNITION, metrics);
            this.handler = handler;
        }

        @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS, groupId = KafkaConsumerGroupNames.IGNITION,
                concurrency = KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION)
        public void onProcessedPosition(ConsumerRecord<String, String> record) {
            consume(record, handler::handle);
        }
    }
}
