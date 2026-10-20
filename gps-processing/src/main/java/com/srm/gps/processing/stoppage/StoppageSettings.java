package com.srm.gps.processing.stoppage;

/**
 * Tuning of the stoppage detector.
 *
 * Stay-point thresholds: a stop is a place the vehicle stays within {@code radiusMeters} of for at least
 * {@code minDurationSeconds}. The radius must exceed GPS noise; the duration must be several reporting intervals
 * (at one reading a minute, 3 minutes = 3-4 readings).
 *
 * @param radiusMeters           how far the vehicle may wander and still be "in the same place"
 * @param minDurationSeconds     how long it must stay to be a stop (Traccar report.trip.minDuration default 180 s)
 * @param stationarySpeedKmph    "standing" means at most this speed, by speedometer and by distance over time (2-3 km/h)
 * @param exitConfirmReadings    readings outside the radius in a row before the stop is over (one stray fix is not a departure)
 * @param maxGapSeconds          a silence longer than this ends a candidate; a confirmed stop survives it if the vehicle
 *                               is still in the same place (a parked device often sleeps)
 * @param mergeGapSeconds        a new stop within the radius of the previous one, starting this soon after it ended,
 *                               continues it (Montoliu-style merge: no "stop, 1-minute trip, stop" at the same dock)
 */
public record StoppageSettings(double radiusMeters, long minDurationSeconds, double stationarySpeedKmph,
                               int exitConfirmReadings, long maxGapSeconds, long mergeGapSeconds) {

    public static StoppageSettings defaults() {
        return new StoppageSettings(100, 180, 3, 2, 1800, 180);
    }
}
