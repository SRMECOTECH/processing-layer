package com.srm.gps.processing.step;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

import java.time.Instant;

/**
 * The newest reading we know of one device - all that is needed to calculate movement.
 *
 * @param recordedAt           when it was taken
 * @param latitude             its (possibly jitter-clamped) position
 * @param longitude            its (possibly jitter-clamped) position
 * @param totalDistanceMeters  running total of counted distance up to and including this reading
 * @param odometerMeters       the device's odometer at this reading, or null
 */
public record PreviousReading(Instant recordedAt, double latitude, double longitude, double totalDistanceMeters, Long odometerMeters) {

    public PreviousReading(Instant recordedAt, double latitude, double longitude) {
        this(recordedAt, latitude, longitude, 0, null);
    }

    /** What the next reading of the device is measured from, once this position was accepted. */
    public static PreviousReading of(ProcessedGpsPosition position) {
        return new PreviousReading(position.recordedAt(), position.latitude(), position.longitude(),
                position.totalDistanceMeters() == null ? 0 : position.totalDistanceMeters(), position.odometerMeters());
    }
}
