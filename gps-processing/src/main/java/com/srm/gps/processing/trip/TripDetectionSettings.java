package com.srm.gps.processing.trip;

/**
 * The three thresholds of trip and stop detection (Traccar's report.trip.minDistance / minDuration / stopGap).
 *
 * @param minDistanceMeters  a trip starts once the vehicle is at least this far from where it stopped,
 *                           and it stops when no recent reading is this far from the current one
 * @param minDurationSeconds how long the vehicle must stay within minDistance before a stop is declared
 * @param stopGapSeconds     a silence longer than this ends the trip at the last reading before the silence
 */
public record TripDetectionSettings(double minDistanceMeters, long minDurationSeconds, long stopGapSeconds) {

    /** Traccar's defaults: 200 m, 3 minutes, 1 hour. */
    public static final TripDetectionSettings DEFAULTS = new TripDetectionSettings(200, 180, 3600);

    public TripDetectionSettings {
        if (minDistanceMeters <= 0 || minDurationSeconds <= 0 || stopGapSeconds <= 0) {
            throw new IllegalArgumentException("Trip detection thresholds must be positive: " + minDistanceMeters
                    + " m, " + minDurationSeconds + " s, " + stopGapSeconds + " s");
        }
    }

    long minDurationMillis() {
        return minDurationSeconds * 1000;
    }

    long stopGapMillis() {
        return stopGapSeconds * 1000;
    }

    /** Slowest average speed that still counts as driving: minDistance in minDuration, in metres per millisecond. */
    double minAverageSpeedMetersPerMilli() {
        return minDistanceMeters / minDurationMillis();
    }
}
