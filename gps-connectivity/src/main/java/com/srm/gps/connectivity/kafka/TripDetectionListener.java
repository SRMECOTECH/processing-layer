package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "gps-trip-service": reads the SAME topic as storage and live location, with its own bookmark.
 * One consumer thread per partition, and all readings of a device sit in one partition,
 * so the readings of one vehicle reach the trip detection one at a time, in order.
 * Passes each one to the {@link TripDetectionHandler}.
 */
@Component
@OnlyInKafkaFlow
public class TripDetectionListener extends SkippableListener {

    private static final PipelineStage STAGE = PipelineStage.TRIP_DETECTION;

    private final TripDetectionHandler handler;
    private final PipelineMetrics metrics;

    public TripDetectionListener(TripDetectionHandler handler, PipelineMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS,
            groupId = KafkaConsumerGroupNames.TRIP_DETECTION,
            concurrency = KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION)
    public void onProcessedPosition(ConsumerRecord<String, String> record) {
        try (PipelineLogContext ignored = PipelineLogContext.forRecord(STAGE, record)) {
            handler.handle(GpsMessageJson.fromJson(record.value(), ProcessedGpsPosition.class));
            metrics.recordMessage(STAGE, record.key(), record.partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(STAGE);
            throw failure;
        }
    }
}
