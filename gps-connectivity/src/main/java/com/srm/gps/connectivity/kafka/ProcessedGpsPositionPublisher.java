package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Producer 2: sends processed positions to "gps.processed-positions", keyed by IMEI so one device's positions stay in order.
 *
 * It WAITS until Kafka has confirmed the message. Only then does the raw-frame consumer finish,
 * and only then is the raw frame's offset committed - so a crash can never lose a frame in between.
 */
@Component
@OnlyInKafkaFlow
public class ProcessedGpsPositionPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineMetrics metrics;

    public ProcessedGpsPositionPublisher(KafkaTemplate<String, String> kafkaTemplate, PipelineMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    public void publish(ProcessedGpsPosition position) {
        try {
            SendResult<String, String> sent = kafkaTemplate
                    .send(KafkaTopicNames.PROCESSED_POSITIONS, position.imei(), GpsMessageJson.toJson(position)).join();
            metrics.recordMessage(PipelineStage.PROCESSED_POSITION_PRODUCER, position.imei(), sent.getRecordMetadata().partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(PipelineStage.PROCESSED_POSITION_PRODUCER);
            throw failure;
        }
    }
}
