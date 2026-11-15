package com.srm.gps.connectivity.monitoring;

import com.srm.gps.configuration.PipelineStage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;

/**
 * Classifies every log line written while one message is handled.
 *
 * It puts stage, IMEI, partition and offset into the logging context (MDC); the log pattern in application.yml
 * prints them on every line, for example:
 *
 *   WARN [stage=position-storage imei=0861000000000002 p=2 o=1534] ... Device ... was not fitted in any vehicle
 *
 * So you can filter the log of one device:  docker compose logs gps-service | Select-String "imei=0861000000000002"
 * or of one consumer:                        docker compose logs gps-service | Select-String "stage=position-storage"
 *
 * Use with try-with-resources so the context is always cleared again.
 */
public final class PipelineLogContext implements AutoCloseable {

    private PipelineLogContext() {
    }

    public static PipelineLogContext forRecord(PipelineStage stage, ConsumerRecord<String, String> record) {
        return forRecord(stage.shortName(), record);
    }

    /** For a processing module without a PipelineStage: {@code stageName} is its id. */
    public static PipelineLogContext forRecord(String stageName, ConsumerRecord<String, String> record) {
        MDC.put("stage", stageName);
        MDC.put("imei", record.key() != null ? record.key() : "-");
        MDC.put("partition", String.valueOf(record.partition()));
        MDC.put("offset", String.valueOf(record.offset()));
        return new PipelineLogContext();
    }

    public static PipelineLogContext forDirectFrame(String imei) {
        MDC.put("stage", PipelineStage.DIRECT_PROCESSING.shortName());
        MDC.put("imei", imei != null ? imei : "-");
        return new PipelineLogContext();
    }

    @Override
    public void close() {
        MDC.remove("stage");
        MDC.remove("imei");
        MDC.remove("partition");
        MDC.remove("offset");
    }
}
