package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Producer 1: sends raw GPS frames to Kafka, exactly as a device would.
 * The IMEI is the message key, so all frames of one device go to the same partition and stay in order.
 *
 * Today the simulator calls this; later the real device parser will publish to the same topic.
 * It does not wait for Kafka (a device just sends and moves on); the result is counted when Kafka answers.
 */
@Component
@OnlyInKafkaFlow
public class RawGpsFramePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RawGpsFramePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineMetrics metrics;

    public RawGpsFramePublisher(KafkaTemplate<String, String> kafkaTemplate, PipelineMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    public void publish(RawGpsFrame frame) {
        kafkaTemplate.send(KafkaTopicNames.RAW_DEVICE_FRAMES, frame.imei(), GpsMessageJson.toJson(frame))
                .whenComplete((sent, failure) -> {
                    if (failure == null) {
                        metrics.recordMessage(PipelineStage.RAW_FRAME_PRODUCER, frame.imei(), sent.getRecordMetadata().partition());
                    } else {
                        metrics.recordFailure(PipelineStage.RAW_FRAME_PRODUCER);
                        LOG.warn("Could not send the frame of device {} to Kafka: {}", frame.imei(), failure.getMessage());
                    }
                });
    }
}
