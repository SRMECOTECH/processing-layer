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
 * Consumer group "gps-position-storage-service": receives every processed position
 * and passes it to the {@link ProcessedGpsPositionHandler}.
 */
@Component
@OnlyInKafkaFlow
public class ProcessedGpsPositionListener {

    private static final PipelineStage STAGE = PipelineStage.POSITION_STORAGE;

    private final ProcessedGpsPositionHandler handler;
    private final PipelineMetrics metrics;

    public ProcessedGpsPositionListener(ProcessedGpsPositionHandler handler, PipelineMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS,
            groupId = KafkaConsumerGroupNames.POSITION_STORAGE,
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
