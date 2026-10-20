package com.srm.gps.processing.state;

import java.time.Instant;

/**
 * The full state of one vehicle (kept in Redis gps:state:vehicle:{imei}; its recent readings go to the
 * Redis stream gps:history:{imei}). Traccar keeps the equivalent in its in-memory "last position" plus
 * device columns; here it survives restarts and is shared by every service.
 *
 * Position, speed and movement as last reported; heading and acceleration derived from consecutive readings;
 * a Kalman-smoothed track and its own distance; data-quality counters; time spent moving and standing.
 */
public record VehicleState(
        String imei,
        String vehicleRegistrationNo,
        String tenantCode,
        Instant firstSeenAt,
        Instant lastRecordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        boolean moving,
        Boolean ignition,
        Double headingDegrees,
        Double accelerationMps2,
        double totalDistanceMeters,
        Long odometerMeters,
        // Kalman filter (local plane around originLatitude/originLongitude)
        double originLatitude,
        double originLongitude,
        double[] kalmanState,
        double[] kalmanCovariance,
        double kalmanLatitude,
        double kalmanLongitude,
        double kalmanSpeedKmph,
        double kalmanDistanceMeters,
        int kalmanRejectedInARow,
        // counters
        long readings,
        long outOfOrderReadings,
        long jitterFilteredReadings,
        long jumpFilteredReadings,
        long odometerReadings,
        double averageIntervalSeconds,
        long longestGapSeconds,
        long movingSeconds,
        long standingSeconds,
        long harshAccelerations,
        long harshBrakings) {

    public KalmanFilter2D kalman() {
        return kalmanState == null ? null : new KalmanFilter2D(kalmanState, kalmanCovariance);
    }
}
