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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "gps-live-location-service": reads the SAME topic as the storage consumer,
 * with its own bookmark - so it gets every processed position too, without slowing storage down.
 * Passes each one to the {@link LiveLocationHandler}.
 *
 * Not created while Redis is switched off (gps.redis.enabled=false): the live position lives only in Redis. The group
 * keeps its place in Kafka and carries on from there once Redis is switched on again.
 */
@Component
@OnlyInKafkaFlow
@ConditionalOnProperty(name = "gps.redis.enabled", havingValue = "true", matchIfMissing = true)
public class LiveLocationListener extends SkippableListener {

    private static final PipelineStage STAGE = PipelineStage.LIVE_LOCATION;

    private final LiveLocationHandler handler;
    private final PipelineMetrics metrics;

    public LiveLocationListener(LiveLocationHandler handler, PipelineMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaTopicNames.PROCESSED_POSITIONS,
            groupId = KafkaConsumerGroupNames.LIVE_LOCATION,
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
