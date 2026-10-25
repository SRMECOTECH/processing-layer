package com.srm.gps.processing;

import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.processing.step.CalculateMovementStep;
import com.srm.gps.processing.step.DistanceSettings;
import com.srm.gps.processing.step.PreviousReading;
import com.srm.gps.processing.step.PreviousReadingStore;
import com.srm.gps.processing.step.ValidateRawFrameStep;
import com.srm.gps.processing.step.ValidationResult;

import java.time.Instant;
import java.util.Optional;

/**
 * The whole processing of one raw frame, read top to bottom:
 *
 *   raw frame (JSON text)
 *      -> 1. validate the frame                       (bad frame  -> onRejected)
 *      -> 2. calculate movement from the device's
 *            previous reading                         (duplicate  -> onRejected)
 *      -> onAccepted(processed position)
 *      -> 3. remember this reading as the device's newest
 *
 * It does not know where the frame came from or where the result goes:
 * the Kafka flow and the direct flow each pass their own {@link ProcessingResultHandler}.
 *
 * Step 3 runs only AFTER the handler succeeded. If the handler fails (Kafka or PostgreSQL is down),
 * nothing is remembered, so when the frame is retried it is processed again normally
 * instead of being mistaken for a duplicate.
 *
 * Frames of ONE device must be processed one at a time, in arrival order.
 * Kafka flow: all frames of a device sit in one partition, which one consumer thread reads.
 * Direct flow: the caller processes one frame at a time.
 */
public final class GpsFrameProcessor {

    private final ValidateRawFrameStep validateRawFrameStep = new ValidateRawFrameStep();
    private final CalculateMovementStep calculateMovementStep;
    private final PreviousReadingStore previousReadingStore;

    public GpsFrameProcessor(PreviousReadingStore previousReadingStore) {
        this(previousReadingStore, DistanceSettings.defaults());
    }

    public GpsFrameProcessor(PreviousReadingStore previousReadingStore, DistanceSettings distanceSettings) {
        this.previousReadingStore = previousReadingStore;
        this.calculateMovementStep = new CalculateMovementStep(distanceSettings);
    }

    public void process(String rawFrameJson, ProcessingResultHandler resultHandler) {
        Instant now = Instant.now();

        // Step 1: validate
        ValidationResult validation = validateRawFrameStep.validate(rawFrameJson, now);
        if (!validation.isValid()) {
            resultHandler.onRejected(new RejectedGpsFrame(validation.imei(), rawFrameJson, validation.problem(), now));
            return;
        }

        // Step 2: calculate movement using the device's previous reading
        String imei = validation.validFrame().imei();
        Optional<PreviousReading> previous = previousReadingStore.findLatestReading(imei);
        ProcessingResult result = calculateMovementStep.calculate(validation.validFrame(), previous, rawFrameJson, now);

        switch (result) {
            case ProcessingResult.Rejected rejected -> resultHandler.onRejected(rejected.rejection());
            case ProcessingResult.Accepted accepted -> {
                ProcessedGpsPosition position = accepted.position();
                resultHandler.onAccepted(position);

                // Step 3: remember it - but a late (older) reading must not replace the newest one we know about.
                if (!position.arrivedOutOfOrder()) {
                    previousReadingStore.rememberLatestReading(imei, PreviousReading.of(position));
                }
            }
        }
    }
}
