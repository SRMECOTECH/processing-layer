package com.srm.gps.configuration.message;

import java.time.Instant;

/**
 * One reading exactly as a GPS device reports it.
 * A device only knows its own IMEI - it does not know which vehicle or company it belongs to.
 * That mapping is master data and is resolved later by the storage service.
 *
 * Fields are wrapper types (Double, Integer) so a missing value arrives as null and
 * the processing can reject it with a clear reason instead of silently reading 0.
 *
 * @param imei            unique id of the GPS device
 * @param recordedAt      the moment the device took the reading (not the moment it was sent)
 * @param latitude        degrees, -90 .. 90
 * @param longitude       degrees, -180 .. 180
 * @param speedKmph       speed reported by the device (speedometer / GPS module) in km/h
 * @param ignition        ignition wire state; null when the device has no ignition input
 * @param odometerMeters  the device's own odometer; null when the device does not report one
 */
public record RawGpsFrame(
        String imei,
        Instant recordedAt,
        Double latitude,
        Double longitude,
        Integer speedKmph,
        Boolean ignition,
        Long odometerMeters) {

    /** A frame from a device without ignition input or odometer. */
    public RawGpsFrame(String imei, Instant recordedAt, Double latitude, Double longitude, Integer speedKmph) {
        this(imei, recordedAt, latitude, longitude, speedKmph, null, null);
    }
}
