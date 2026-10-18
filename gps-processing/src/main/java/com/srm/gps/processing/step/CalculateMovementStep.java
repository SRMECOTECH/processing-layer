package com.srm.gps.processing.step;

import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.processing.ProcessingResult;
import com.srm.gps.processing.rule.GeoDistanceCalculator;
import com.srm.gps.processing.rule.MovementRules;

import java.time.Instant;
import java.util.Optional;

/**
 * Step 2: compares the frame with the previous reading of the same device
 * to work out distance travelled, time elapsed and movement status.
 *
 * It only calculates; remembering the new reading is done by the processor afterwards.
 */
public class CalculateMovementStep {

    public ProcessingResult calculate(RawGpsFrame frame, Optional<PreviousReading> previous, String rawFrameJson, Instant now) {
        if (previous.isPresent() && frame.recordedAt().equals(previous.get().recordedAt())) {
            return new ProcessingResult.Rejected(new RejectedGpsFrame(frame.imei(), rawFrameJson,
                    "Duplicate reading: this device already sent a reading recorded at " + frame.recordedAt(), now));
        }

        boolean arrivedOutOfOrder = previous.isPresent() && frame.recordedAt().isBefore(previous.get().recordedAt());

        double distanceFromPreviousMeters = 0;
        long secondsSincePreviousReading = 0;
        if (previous.isPresent() && !arrivedOutOfOrder) {
            distanceFromPreviousMeters = GeoDistanceCalculator.distanceInMeters(
                    previous.get().latitude(), previous.get().longitude(), frame.latitude(), frame.longitude());
            secondsSincePreviousReading = frame.recordedAt().getEpochSecond() - previous.get().recordedAt().getEpochSecond();
        }

        MovementStatus movementStatus = MovementRules.decideMovementStatus(frame.speedKmph(), distanceFromPreviousMeters);

        return new ProcessingResult.Accepted(new ProcessedGpsPosition(
                frame.imei(),
                frame.recordedAt(),
                frame.latitude(),
                frame.longitude(),
                frame.speedKmph(),
                movementStatus,
                Math.round(distanceFromPreviousMeters * 10) / 10.0,
                secondsSincePreviousReading,
                arrivedOutOfOrder,
                now));
    }
}
