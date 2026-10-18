package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "gps-raw-frame-processing-service": receives every raw device frame
 * and passes it to the {@link RawGpsFrameHandler}.
 *
 * One consumer thread per partition. All frames of one device are in one partition,
 * so one device is always processed by one thread, in order.
 */
@Component
@OnlyInKafkaFlow
public class RawGpsFrameListener {

    private static final PipelineStage STAGE = PipelineStage.RAW_FRAME_PROCESSING;

    private final RawGpsFrameHandler handler;
    private final PipelineMetrics metrics;

    public RawGpsFrameListener(RawGpsFrameHandler handler, PipelineMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaTopicNames.RAW_DEVICE_FRAMES,
            groupId = KafkaConsumerGroupNames.RAW_FRAME_PROCESSING,
            concurrency = KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION)
    public void onRawFrame(ConsumerRecord<String, String> record) {
        try (PipelineLogContext ignored = PipelineLogContext.forRecord(STAGE, record)) {
            handler.handle(record.value());
            metrics.recordMessage(STAGE, record.key(), record.partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(STAGE);
            throw failure;
        }
    }
}
