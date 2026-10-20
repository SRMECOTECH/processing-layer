package com.srm.gps.configuration.message;

import java.time.Instant;

/**
 * A GPS reading that passed validation, enriched by the processing step with values that need
 * the device's previous reading (distance travelled, time elapsed, movement status).
 *
 * The fields after processedAt were added later; they are wrapper types so messages written before
 * still read (as null).
 *
 * @param imei                         unique id of the GPS device
 * @param recordedAt                   the moment the device took the reading
 * @param latitude                     degrees (the previous position when parking jitter was clamped)
 * @param longitude                    degrees
 * @param speedKmph                    speed reported by the device in km/h
 * @param movementStatus               MOVING or STOPPED
 * @param distanceFromPreviousMeters   distance counted since the device's previous reading (0 for the first one)
 * @param secondsSincePreviousReading  seconds since the device's previous reading (0 for the first one)
 * @param arrivedOutOfOrder            true when the reading is older than one already processed for this device
 * @param processedAt                  the moment the reading was processed
 * @param ignition                     ignition as reported by the device, or null
 * @param odometerMeters               the device's odometer as reported, or null
 * @param totalDistanceMeters          running total of counted distance for this device (Traccar "totalDistance")
 * @param distanceSource               how the distance was obtained
 * @param impliedSpeedKmph             great-circle distance / elapsed time, before any filtering; null when not measurable
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
        Instant processedAt,
        Boolean ignition,
        Long odometerMeters,
        Double totalDistanceMeters,
        DistanceSource distanceSource,
        Double impliedSpeedKmph) {

    /** A position without the later additions (ignition, odometer, totals). */
    public ProcessedGpsPosition(String imei, Instant recordedAt, double latitude, double longitude, int speedKmph,
                                MovementStatus movementStatus, double distanceFromPreviousMeters,
                                long secondsSincePreviousReading, boolean arrivedOutOfOrder, Instant processedAt) {
        this(imei, recordedAt, latitude, longitude, speedKmph, movementStatus, distanceFromPreviousMeters,
                secondsSincePreviousReading, arrivedOutOfOrder, processedAt, null, null, null, null, null);
    }

    /** Distance source, treating messages written before the field existed as GPS. */
    public DistanceSource distanceSourceOrGps() {
        return distanceSource == null ? DistanceSource.GPS : distanceSource;
    }
}
