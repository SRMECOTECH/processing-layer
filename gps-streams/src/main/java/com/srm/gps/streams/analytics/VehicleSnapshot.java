package com.srm.gps.streams.analytics;

import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;

import java.time.Instant;

/**
 * One row of the vehicle KTable: the latest known state of a vehicle, folded from every processed position.
 *
 * "Latest" means newest by EVENT time: a late reading is counted, but never replaces a newer position -
 * Kafka Streams does not decide that by itself, this fold does.
 *
 * @param imei                device
 * @param recordedAt          newest reading's time; null before the first reading
 * @param statusSince         when the current movement status began (event time) - "moving for 12 min"
 * @param readings            every position folded in, late ones included
 * @param outOfOrderReadings  positions that were older than one already seen
 * @param maxSpeedKmph        highest reported speed seen
 * @param firstRecordedAt     oldest reading seen
 * @param lastProcessedAt     when the newest position left the normalizer
 */
public record VehicleSnapshot(
        String imei,
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph,
        MovementStatus movementStatus,
        Instant statusSince,
        Double totalDistanceMeters,
        DistanceSource distanceSource,
        Boolean ignition,
        long readings,
        long outOfOrderReadings,
        int maxSpeedKmph,
        Instant firstRecordedAt,
        Instant lastProcessedAt) {

    /** The aggregate's starting value, before the vehicle's first reading. */
    public static VehicleSnapshot empty() {
        return new VehicleSnapshot(null, null, 0, 0, 0, null, null, null, null, null, 0, 0, 0, null, null);
    }

    public VehicleSnapshot fold(String vehicleImei, ProcessedGpsPosition position) {
        long folded = readings + 1;
        Instant first = firstRecordedAt == null || position.recordedAt().isBefore(firstRecordedAt) ? position.recordedAt() : firstRecordedAt;
        int maxSpeed = Math.max(maxSpeedKmph, position.speedKmph());
        boolean newest = recordedAt == null || position.recordedAt().isAfter(recordedAt);

        if (!newest) {
            return new VehicleSnapshot(imei, recordedAt, latitude, longitude, speedKmph, movementStatus, statusSince,
                    totalDistanceMeters, distanceSource, ignition, folded, outOfOrderReadings + 1, maxSpeed, first, lastProcessedAt);
        }
        Instant since = position.movementStatus() == movementStatus && statusSince != null ? statusSince : position.recordedAt();
        return new VehicleSnapshot(vehicleImei, position.recordedAt(), position.latitude(), position.longitude(),
                position.speedKmph(), position.movementStatus(), since, position.totalDistanceMeters(),
                position.distanceSourceOrGps(), position.ignition(), folded, outOfOrderReadings, maxSpeed, first,
                position.processedAt());
    }
}
