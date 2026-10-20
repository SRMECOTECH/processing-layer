package com.srm.gps.processing.state;

import com.srm.gps.configuration.message.DistanceSource;

import java.time.Instant;

/** What the vehicle-state keeper needs from one processed position (plus the vehicle it belongs to). */
public record VehicleReading(
        String imei,
        String vehicleRegistrationNo,
        String tenantCode,
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        boolean moving,
        Boolean ignition,
        double distanceMeters,
        DistanceSource distanceSource,
        Double totalDistanceMeters,
        Long odometerMeters,
        long secondsSincePrevious,
        boolean arrivedOutOfOrder) {
}
