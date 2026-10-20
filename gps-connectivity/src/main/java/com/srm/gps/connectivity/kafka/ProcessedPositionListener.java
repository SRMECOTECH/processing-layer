package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.function.Consumer;

/**
 * Shared body of the consumers that read gps.processed-positions: read the JSON, hand it to the stage's handler,
 * count it. Each consumer group sees every message, keyed by IMEI, so one vehicle's readings reach a handler
 * one at a time and in order (one consumer thread per partition).
 */
public abstract class ProcessedPositionListener extends SkippableListener {

    private final PipelineStage stage;
    private final PipelineMetrics metrics;

    protected ProcessedPositionListener(PipelineStage stage, PipelineMetrics metrics) {
        this.stage = stage;
        this.metrics = metrics;
    }

    protected void consume(ConsumerRecord<String, String> record, Consumer<ProcessedGpsPosition> handler) {
        try (PipelineLogContext ignored = PipelineLogContext.forRecord(stage, record)) {
            handler.accept(GpsMessageJson.fromJson(record.value(), ProcessedGpsPosition.class));
            metrics.recordMessage(stage, record.key(), record.partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(stage);
            // Kafka retries the message with a back-off and only logs when it gives up, so say why here.
            LOG.warn("{} failed, will be retried: {}", stage.label(), failure.toString(), failure);
            throw failure;
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProcessedPositionListener.class);
}
