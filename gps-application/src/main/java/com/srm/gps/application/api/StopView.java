package com.srm.gps.application.api;

import com.srm.gps.connectivity.database.entity.GpsStop;

import java.time.Instant;

/** A stop as returned by the REST API. Durations in seconds. */
public record StopView(
        String imei,
        String tenantCode,
        String vehicleRegistrationNo,
        String status,
        Instant startRecordedAt,
        Instant endRecordedAt,
        double latitude,
        double longitude,
        long durationSeconds,
        int readingCount) {

    public static StopView from(GpsStop stop) {
        return new StopView(
                stop.getImei(),
                stop.getTenantCode(),
                stop.getVehicleRegistrationNo(),
                stop.getStatus(),
                stop.getStartRecordedAt(),
                stop.getEndRecordedAt(),
                stop.getLatitude(),
                stop.getLongitude(),
                stop.getDurationSeconds(),
                stop.getReadingCount());
    }
}
