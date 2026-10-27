package com.srm.gps.streams.analytics;

import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;

import java.time.Instant;

/**
 * A vehicle's activity inside one event-time window: how many readings, how many while moving, top and summed
 * speed, and the distance counted by the normalizer (late readings add no distance - they were never measured).
 * Windows of different vehicles {@link #merge merge} into fleet totals at query time, so no fleet-wide key (and no
 * single hot partition) is needed.
 */
public record ActivityWindow(
        long readings,
        long movingReadings,
        long outOfOrderReadings,
        int maxSpeedKmph,
        long speedSumKmph,
        double distanceMeters,
        Instant firstRecordedAt,
        Instant lastRecordedAt) {

    public static ActivityWindow empty() {
        return new ActivityWindow(0, 0, 0, 0, 0, 0, null, null);
    }

    public ActivityWindow add(ProcessedGpsPosition position) {
        Instant at = position.recordedAt();
        return new ActivityWindow(
                readings + 1,
                movingReadings + (position.movementStatus() == MovementStatus.MOVING ? 1 : 0),
                outOfOrderReadings + (position.arrivedOutOfOrder() ? 1 : 0),
                Math.max(maxSpeedKmph, position.speedKmph()),
                speedSumKmph + position.speedKmph(),
                distanceMeters + position.distanceFromPreviousMeters(),
                firstRecordedAt == null || at.isBefore(firstRecordedAt) ? at : firstRecordedAt,
                lastRecordedAt == null || at.isAfter(lastRecordedAt) ? at : lastRecordedAt);
    }

    public ActivityWindow merge(ActivityWindow other) {
        return new ActivityWindow(
                readings + other.readings,
                movingReadings + other.movingReadings,
                outOfOrderReadings + other.outOfOrderReadings,
                Math.max(maxSpeedKmph, other.maxSpeedKmph),
                speedSumKmph + other.speedSumKmph,
                distanceMeters + other.distanceMeters,
                earliest(firstRecordedAt, other.firstRecordedAt),
                latest(lastRecordedAt, other.lastRecordedAt));
    }

    public double averageSpeedKmph() {
        return readings == 0 ? 0 : speedSumKmph / (double) readings;
    }

    public double movingShare() {
        return readings == 0 ? 0 : movingReadings / (double) readings;
    }

    private static Instant earliest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
