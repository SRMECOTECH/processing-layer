package com.srm.gps.processing.speed;

import java.time.Instant;
import java.util.Map;

/**
 * Something the speed check found, before it becomes a VehicleEvent.
 *
 * @param kind       what happened
 * @param startedAt  first reading of the episode (or streak)
 * @param endedAt    last reading of the episode, null while it lasts
 * @param details    limit, durations, speeds, verification counts and the overall verdict
 */
public record SpeedFinding(Kind kind, Instant startedAt, Instant endedAt, double latitude, double longitude,
                           double excessKmph, String verdict, Map<String, Object> details) {

    public enum Kind {
        /** The episode met the rule (duration and ping count): raise it once. */
        VIOLATION_CONFIRMED,
        /** A confirmed episode is over (speed dropped below limit - hysteresis, or a data gap). */
        VIOLATION_ENDED,
        /** Speedometer and GPS disagreed for the rule's number of readings in a row. */
        SENSOR_MISMATCH
    }
}
