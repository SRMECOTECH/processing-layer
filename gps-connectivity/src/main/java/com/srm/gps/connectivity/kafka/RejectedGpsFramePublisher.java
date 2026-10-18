package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Producer 3: sends rejected frames to "gps.rejected-frames".
 * Keyed by IMEI when the frame had one (so rejections can be filtered per device); otherwise no key.
 * Waits for Kafka's confirmation, like {@link ProcessedGpsPositionPublisher}.
 */
@Component
@OnlyInKafkaFlow
public class RejectedGpsFramePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineMetrics metrics;

    public RejectedGpsFramePublisher(KafkaTemplate<String, String> kafkaTemplate, PipelineMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    public void publish(RejectedGpsFrame rejection) {
        try {
            SendResult<String, String> sent = kafkaTemplate
                    .send(KafkaTopicNames.REJECTED_FRAMES, rejection.imei(), GpsMessageJson.toJson(rejection)).join();
            metrics.recordMessage(PipelineStage.REJECTED_FRAME_PRODUCER, rejection.imei(), sent.getRecordMetadata().partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(PipelineStage.REJECTED_FRAME_PRODUCER);
            throw failure;
        }
    }
}
