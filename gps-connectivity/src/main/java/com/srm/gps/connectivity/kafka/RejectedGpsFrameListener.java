package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "gps-rejected-frame-logger": receives every rejected frame
 * and passes it to the {@link RejectedGpsFrameHandler}. Rejections are rare, so one consumer is enough.
 * Rejected frames are keyed by IMEI when the frame had one, so they can be filtered per device too.
 */
@Component
@OnlyInKafkaFlow
public class RejectedGpsFrameListener {

    private static final PipelineStage STAGE = PipelineStage.REJECTED_FRAME_LOGGER;

    private final RejectedGpsFrameHandler handler;
    private final PipelineMetrics metrics;

    public RejectedGpsFrameListener(RejectedGpsFrameHandler handler, PipelineMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaTopicNames.REJECTED_FRAMES, groupId = KafkaConsumerGroupNames.REJECTED_FRAME_LOGGER)
    public void onRejectedFrame(ConsumerRecord<String, String> record) {
        try (PipelineLogContext ignored = PipelineLogContext.forRecord(STAGE, record)) {
            handler.handle(GpsMessageJson.fromJson(record.value(), RejectedGpsFrame.class));
            metrics.recordMessage(STAGE, record.key(), record.partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(STAGE);
            throw failure;
        }
    }
}
