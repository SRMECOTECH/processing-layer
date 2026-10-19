package com.srm.gps.processing.trip;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

import java.time.Instant;

/**
 * One processed reading as the trip detection sees it.
 *
 * @param assignment the device_assignment valid at recordedAt; null when the device was in no vehicle
 */
public record TripReading(
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        double distanceFromPreviousMeters,
        VehicleAssignment assignment) {

    public static TripReading from(ProcessedGpsPosition position, VehicleAssignment assignment) {
        return new TripReading(position.recordedAt(), position.latitude(), position.longitude(),
                position.speedKmph(), position.distanceFromPreviousMeters(), assignment);
    }

    WindowReading toWindowReading() {
        return new WindowReading(recordedAt, latitude, longitude, speedKmph, distanceFromPreviousMeters);
    }
}
