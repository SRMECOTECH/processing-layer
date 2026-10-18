package com.srm.gps.configuration.message;

import java.time.Instant;

/**
 * A GPS reading that passed validation, enriched by the processing step with values that need
 * the device's previous reading (distance travelled, time elapsed, movement status).
 *
 * @param imei                         unique id of the GPS device
 * @param recordedAt                   the moment the device took the reading
 * @param latitude                     degrees
 * @param longitude                    degrees
 * @param speedKmph                    speed reported by the device in km/h
 * @param movementStatus               MOVING or STOPPED
 * @param distanceFromPreviousMeters   straight-line distance from the device's previous reading (0 for the first one)
 * @param secondsSincePreviousReading  seconds since the device's previous reading (0 for the first one)
 * @param arrivedOutOfOrder            true when the reading is older than one already processed for this device
 * @param processedAt                  the moment the reading was processed
 */
public record ProcessedGpsPosition(
        String imei,
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        MovementStatus movementStatus,
        double distanceFromPreviousMeters,
        long secondsSincePreviousReading,
        boolean arrivedOutOfOrder,
        Instant processedAt) {
}
