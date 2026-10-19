package com.srm.gps.application.api;

import com.srm.gps.connectivity.database.entity.GpsTrip;

import java.time.Instant;

/** A trip as returned by the REST API. Distances in metres, durations in seconds, speeds in km/h. */
public record TripView(
        String imei,
        String tenantCode,
        String vehicleRegistrationNo,
        String status,
        Instant startRecordedAt,
        Instant endRecordedAt,
        double startLatitude,
        double startLongitude,
        double endLatitude,
        double endLongitude,
        double distanceMeters,
        long durationSeconds,
        int maxSpeedKmph,
        double averageSpeedKmph,
        int readingCount,
        int lateReadingCount) {

    public static TripView from(GpsTrip trip) {
        return new TripView(
                trip.getImei(),
                trip.getTenantCode(),
                trip.getVehicleRegistrationNo(),
                trip.getStatus(),
                trip.getStartRecordedAt(),
                trip.getEndRecordedAt(),
                trip.getStartLatitude(),
                trip.getStartLongitude(),
                trip.getEndLatitude(),
                trip.getEndLongitude(),
                trip.getDistanceMeters(),
                trip.getDurationSeconds(),
                trip.getMaxSpeedKmph(),
                trip.getAverageSpeedKmph(),
                trip.getReadingCount(),
                trip.getLateReadingCount());
    }
}
