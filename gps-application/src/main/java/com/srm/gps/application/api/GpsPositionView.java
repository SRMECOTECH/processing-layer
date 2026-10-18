package com.srm.gps.application.api;

import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.connectivity.database.entity.GpsPosition;

import java.time.Instant;

/** A stored GPS position as returned by the REST API. */
public record GpsPositionView(
        String imei,
        String tenantCode,
        String vehicleRegistrationNo,
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        MovementStatus movementStatus,
        double distanceFromPreviousMeters,
        long secondsSincePreviousReading,
        Instant storedAt) {

    public static GpsPositionView from(GpsPosition position) {
        return new GpsPositionView(
                position.getImei(),
                position.getTenantCode(),
                position.getVehicleRegistrationNo(),
                position.getRecordedAt(),
                position.getLatitude(),
                position.getLongitude(),
                position.getSpeedKmph(),
                position.getMovementStatus(),
                position.getDistanceFromPreviousMeters(),
                position.getSecondsSincePreviousReading(),
                position.getStoredAt());
    }
}
