package com.srm.gps.processing.speed;

import java.time.Instant;

/**
 * What the speed check needs from one processed position.
 *
 * @param odometerMeters  the device odometer, or null (when present the GPS check uses it as a third witness)
 */
public record SpeedReading(Instant recordedAt, double latitude, double longitude, int speedKmph,
                           Long odometerMeters, boolean arrivedOutOfOrder) {
}
