package com.srm.gps.processing.step;

import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.processing.ProcessingResult;
import com.srm.gps.processing.rule.DistanceCalculator;
import com.srm.gps.processing.rule.MovementRules;

import java.time.Instant;
import java.util.Optional;

/**
 * Step 2: compares the frame with the previous reading of the same device to work out distance travelled,
 * time elapsed and movement status. It only calculates; remembering the new reading is done by the processor.
 *
 * Distance follows Traccar's {@code DistanceHandler} (rev_engineer/traccar-rnd, notes 01 section 1.2):
 *   1. the device's own distance wins: here the odometer delta, when the device reports one and it is believable;
 *   2. otherwise the haversine distance to the previous position ({@link DistanceCalculator});
 *   3. coordinates filter: a move smaller than GPS noise while standing is clamped back to the previous
 *      position with distance 0 - a parked truck's GPS "wanders" and would otherwise add kilometres a day;
 *   4. max-speed filter (Traccar FilterHandler "maxSpeed"): a move that needs an impossible speed is a glitch
 *      or a relocation and is not counted;
 *   5. totalDistance = previous total + counted distance (Traccar "totalDistance").
 * Unlike Traccar, a late (older) reading is never measured against the newest one - Traccar does that and the
 * R&D runtime study produced a -1020 m trip from one 5-minute-late reading. It gets distance 0 and is flagged.
 */
public class CalculateMovementStep {

    private final DistanceSettings settings;

    public CalculateMovementStep() {
        this(DistanceSettings.defaults());
    }

    public CalculateMovementStep(DistanceSettings settings) {
        this.settings = settings;
    }

    public ProcessingResult calculate(RawGpsFrame frame, Optional<PreviousReading> previous, String rawFrameJson, Instant now) {
        if (previous.isPresent() && frame.recordedAt().equals(previous.get().recordedAt())) {
            return new ProcessingResult.Rejected(new RejectedGpsFrame(frame.imei(), rawFrameJson,
                    "Duplicate reading: this device already sent a reading recorded at " + frame.recordedAt(), now));
        }

        if (previous.isEmpty()) {
            return accepted(frame, frame.latitude(), frame.longitude(), 0, 0, false, 0, DistanceSource.FIRST_READING, null, now);
        }

        PreviousReading last = previous.get();
        if (frame.recordedAt().isBefore(last.recordedAt())) {
            return accepted(frame, frame.latitude(), frame.longitude(), 0, 0, true, last.totalDistanceMeters(),
                    DistanceSource.OUT_OF_ORDER, null, now);
        }

        long seconds = frame.recordedAt().getEpochSecond() - last.recordedAt().getEpochSecond();
        double gpsMeters = DistanceCalculator.distance(last.latitude(), last.longitude(), frame.latitude(), frame.longitude());
        Double impliedSpeedKmph = seconds > 0 ? gpsMeters / seconds * 3.6 : null;

        double latitude = frame.latitude();
        double longitude = frame.longitude();
        double counted;
        DistanceSource source;

        Double odometerMeters = odometerDelta(frame, last);
        if (odometerMeters != null && believable(odometerMeters, seconds)) {
            counted = odometerMeters;
            source = DistanceSource.DEVICE_ODOMETER;
        } else if (gpsMeters < settings.jitterMeters() && frame.speedKmph() < settings.jitterMaxSpeedKmph()) {
            latitude = last.latitude();
            longitude = last.longitude();
            counted = 0;
            source = DistanceSource.JITTER_FILTERED;
        } else if (gpsMeters >= settings.minJumpMeters() && !believable(gpsMeters, seconds)) {
            counted = 0;
            source = DistanceSource.JUMP_FILTERED;
        } else {
            counted = gpsMeters;
            source = DistanceSource.GPS;
        }

        return accepted(frame, latitude, longitude, counted, seconds, false, last.totalDistanceMeters() + counted,
                source, impliedSpeedKmph, now);
    }

    /** The device odometer's increase since the previous reading, or null when it cannot be used. */
    private static Double odometerDelta(RawGpsFrame frame, PreviousReading last) {
        if (frame.odometerMeters() == null || last.odometerMeters() == null) {
            return null;
        }
        long delta = frame.odometerMeters() - last.odometerMeters();
        return delta >= 0 ? (double) delta : null;
    }

    private boolean believable(double meters, long seconds) {
        if (seconds <= 0) {
            return meters < settings.minJumpMeters();
        }
        return meters / seconds * 3.6 <= settings.maxImpliedSpeedKmph();
    }

    private static ProcessingResult accepted(RawGpsFrame frame, double latitude, double longitude, double distance,
                                             long seconds, boolean outOfOrder, double total, DistanceSource source,
                                             Double impliedSpeedKmph, Instant now) {
        MovementStatus movementStatus = MovementRules.decideMovementStatus(frame.speedKmph(), distance);
        return new ProcessingResult.Accepted(new ProcessedGpsPosition(
                frame.imei(),
                frame.recordedAt(),
                latitude,
                longitude,
                frame.speedKmph(),
                movementStatus,
                round1(distance),
                seconds,
                outOfOrder,
                now,
                frame.ignition(),
                frame.odometerMeters(),
                round1(total),
                source,
                impliedSpeedKmph == null ? null : round1(impliedSpeedKmph)));
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
