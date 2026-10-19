package com.srm.gps.application.flow.direct;

import com.srm.gps.application.flow.RawGpsFrameSender;
import com.srm.gps.application.livelocation.LiveLocationService;
import com.srm.gps.application.rejection.RejectedGpsFrameLoggingService;
import com.srm.gps.application.storage.GpsPositionStorageService;
import com.srm.gps.application.trip.TripDetectionService;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.ProcessingFlowNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInDirectFlow;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.processing.GpsFrameProcessor;
import com.srm.gps.processing.ProcessingResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The direct flow: no Kafka at all. One method call does everything:
 *
 *   raw frame -> processing -> accepted: store in PostgreSQL, update the live position in Redis, then trips and stops
 *                           -> rejected: write to the log
 *
 * Same processing, storage and live-location code as the Kafka flow - only the transport differs.
 * The difference to remember: nothing is buffered. If PostgreSQL is down, send() fails
 * and the caller gets the error; in the Kafka flow the frame would wait in Kafka instead.
 *
 * The live position and the trips are best effort here: the stored position is the record,
 * so a Redis or trip-detection hiccup is logged instead of failing the frame.
 * (A reading the trip detection missed is simply not part of a trip; the next one carries on.)
 *
 * synchronized: one frame at a time, so frames of one device are always processed in order.
 */
@Component
@OnlyInDirectFlow
public class DirectProcessingFlow implements RawGpsFrameSender {

    private static final Logger LOG = LoggerFactory.getLogger(DirectProcessingFlow.class);

    private final GpsFrameProcessor gpsFrameProcessor;
    private final PipelineMetrics metrics;
    private final ProcessingResultHandler storeOrLogResult;

    public DirectProcessingFlow(GpsFrameProcessor gpsFrameProcessor,
                                GpsPositionStorageService gpsPositionStorageService,
                                LiveLocationService liveLocationService,
                                TripDetectionService tripDetectionService,
                                RejectedGpsFrameLoggingService rejectedGpsFrameLoggingService,
                                PipelineMetrics metrics) {
        this.gpsFrameProcessor = gpsFrameProcessor;
        this.metrics = metrics;
        this.storeOrLogResult = new ProcessingResultHandler() {
            @Override
            public void onAccepted(ProcessedGpsPosition position) {
                gpsPositionStorageService.handle(position);
                metrics.recordMessage(PipelineStage.POSITION_STORAGE, position.imei(), -1);
                try {
                    liveLocationService.handle(position);
                    metrics.recordMessage(PipelineStage.LIVE_LOCATION, position.imei(), -1);
                } catch (RuntimeException redisProblem) {
                    metrics.recordFailure(PipelineStage.LIVE_LOCATION);
                    LOG.warn("Live position not updated: {}", redisProblem.getMessage());
                }
                try {
                    tripDetectionService.handle(position);
                    metrics.recordMessage(PipelineStage.TRIP_DETECTION, position.imei(), -1);
                } catch (RuntimeException tripProblem) {
                    metrics.recordFailure(PipelineStage.TRIP_DETECTION);
                    LOG.warn("Trips not updated: {}", tripProblem.getMessage());
                }
            }

            @Override
            public void onRejected(RejectedGpsFrame rejection) {
                rejectedGpsFrameLoggingService.handle(rejection);
                metrics.recordMessage(PipelineStage.REJECTED_FRAME_LOGGER, rejection.imei(), -1);
            }
        };
    }

    @Override
    public synchronized void send(RawGpsFrame frame) {
        try (PipelineLogContext ignored = PipelineLogContext.forDirectFrame(frame.imei())) {
            gpsFrameProcessor.process(GpsMessageJson.toJson(frame), storeOrLogResult);
            metrics.recordMessage(PipelineStage.DIRECT_PROCESSING, frame.imei(), -1);
        } catch (RuntimeException failure) {
            metrics.recordFailure(PipelineStage.DIRECT_PROCESSING);
            throw failure;
        }
    }

    @Override
    public String flowName() {
        return ProcessingFlowNames.DIRECT;
    }
}
