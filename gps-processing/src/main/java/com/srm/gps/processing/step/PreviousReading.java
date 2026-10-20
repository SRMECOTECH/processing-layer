package com.srm.gps.processing.step;

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
}
