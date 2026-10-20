package com.srm.gps.processing.stoppage;

import java.time.Instant;

/**
 * What the stoppage detector needs from one processed position.
 *
 * @param ignition        true / false, or null when nobody knows (no ignition wire and no virtual ignition yet)
 * @param ignitionSource  DEVICE or VIRTUAL, or null
 */
public record StoppageReading(Instant recordedAt, double latitude, double longitude, int speedKmph,
                              Boolean ignition, String ignitionSource, boolean arrivedOutOfOrder) {
}
