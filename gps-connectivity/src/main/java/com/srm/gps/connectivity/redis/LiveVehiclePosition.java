package com.srm.gps.connectivity.redis;

import java.time.Instant;

/**
 * The latest known position of one device, as kept in Redis (hash gps:live:vehicle:{imei}).
 *
 * @param vehicleRegistrationNo null when the device is not fitted in any known vehicle
 * @param tenantCode            null when the device is not fitted in any known vehicle
 */
public record LiveVehiclePosition(
        String imei,
        String vehicleRegistrationNo,
        String tenantCode,
        double latitude,
        double longitude,
        int speedKmph,
        String movementStatus,
        Instant recordedAt,
        Instant updatedAt) {
}
