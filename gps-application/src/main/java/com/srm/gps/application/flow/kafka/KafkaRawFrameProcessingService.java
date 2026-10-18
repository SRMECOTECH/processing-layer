package com.srm.gps.application.flow.kafka;

import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.kafka.ProcessedGpsPositionPublisher;
import com.srm.gps.connectivity.kafka.RawGpsFrameHandler;
import com.srm.gps.connectivity.kafka.RejectedGpsFramePublisher;
import com.srm.gps.processing.GpsFrameProcessor;
import com.srm.gps.processing.ProcessingResultHandler;
import org.springframework.stereotype.Service;

/**
 * Kafka flow, consumer 1 (group "gps-raw-frame-processing-service"):
 * processes each raw frame and publishes the result to the next topic:
 *
 *   accepted -> "gps.processed-positions"  (read by the storage consumer)
 *   rejected -> "gps.rejected-frames"      (read by the rejected-frame logger)
 */
@Service
@OnlyInKafkaFlow
public class KafkaRawFrameProcessingService implements RawGpsFrameHandler {

    private final GpsFrameProcessor gpsFrameProcessor;
    private final ProcessingResultHandler publishResultToKafka;

    public KafkaRawFrameProcessingService(GpsFrameProcessor gpsFrameProcessor,
                                          ProcessedGpsPositionPublisher processedGpsPositionPublisher,
                                          RejectedGpsFramePublisher rejectedGpsFramePublisher) {
        this.gpsFrameProcessor = gpsFrameProcessor;
        this.publishResultToKafka = new ProcessingResultHandler() {
            @Override
            public void onAccepted(ProcessedGpsPosition position) {
                processedGpsPositionPublisher.publish(position);
            }

            @Override
            public void onRejected(RejectedGpsFrame rejection) {
                rejectedGpsFramePublisher.publish(rejection);
            }
        };
    }

    @Override
    public void handle(String rawFrameJson) {
        gpsFrameProcessor.process(rawFrameJson, publishResultToKafka);
    }
}
