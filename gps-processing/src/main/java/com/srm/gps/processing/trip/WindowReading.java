package com.srm.gps.processing.trip;

import java.time.Instant;

/**
 * A recent reading kept in the motion state's window (the last minDuration of in-order readings).
 * The window answers "has the vehicle moved minDistance lately?" and "where did it start rolling?".
 */
public record WindowReading(
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        double distanceFromPreviousMeters) {
}
