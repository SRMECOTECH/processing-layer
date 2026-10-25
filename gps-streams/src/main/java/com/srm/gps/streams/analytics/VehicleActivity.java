package com.srm.gps.streams.analytics;

import org.apache.kafka.streams.kstream.Window;

import java.time.Instant;

/**
 * The final activity of one vehicle in one closed window - the record published to gps.vehicle-activity
 * (and the shape the API answers with). Ready for a time-series store: one row per vehicle per window.
 */
public record VehicleActivity(
        String imei,
        Instant windowStart,
        Instant windowEnd,
        long readings,
        long movingReadings,
        long outOfOrderReadings,
        int maxSpeedKmph,
        double averageSpeedKmph,
        double movingShare,
        double distanceMeters,
        Instant firstRecordedAt,
        Instant lastRecordedAt) {

    public static VehicleActivity of(String imei, Window window, ActivityWindow activity) {
        return of(imei, window.startTime(), window.endTime(), activity);
    }

    public static VehicleActivity of(String imei, Instant start, Instant end, ActivityWindow activity) {
        return new VehicleActivity(imei, start, end, activity.readings(), activity.movingReadings(),
                activity.outOfOrderReadings(), activity.maxSpeedKmph(), round1(activity.averageSpeedKmph()),
                Math.round(activity.movingShare() * 1000) / 1000.0, round1(activity.distanceMeters()),
                activity.firstRecordedAt(), activity.lastRecordedAt());
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
