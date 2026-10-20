package com.srm.gps.configuration.message;

import java.time.Instant;
import java.util.Map;

/**
 * Something worth telling an operator about one vehicle: an over-speed episode, a stoppage, ignition change...
 * Published to Kafka topic gps.vehicle-events (key = IMEI) and kept in PostgreSQL table vehicle_event.
 *
 * Episode events (over-speed, stoppage, idling) are written twice with the same {@link #eventKey()}:
 * once when they start (status OPEN) and once when they end (status CLOSED) - the store upserts by key,
 * which also makes a Kafka redelivery harmless.
 *
 * @param imei                   device
 * @param vehicleRegistrationNo  vehicle at the time, or null
 * @param tenantCode             company at the time, or null
 * @param type                   what happened
 * @param severity               how urgent
 * @param status                 OPEN while the episode lasts, CLOSED when it ended (instant events are CLOSED)
 * @param startedAt              when it started (back-dated to the first reading of the episode)
 * @param endedAt                when it ended, or null while OPEN
 * @param latitude               where it started
 * @param longitude              where it started
 * @param detectedBy             the consumer that found it (PipelineStage name)
 * @param details                type-specific values (limit, max speed, verdict ...), JSON friendly
 * @param detectedAt             when the consumer found it
 */
public record VehicleEvent(
        String imei,
        String vehicleRegistrationNo,
        String tenantCode,
        VehicleEventType type,
        EventSeverity severity,
        Status status,
        Instant startedAt,
        Instant endedAt,
        double latitude,
        double longitude,
        String detectedBy,
        Map<String, Object> details,
        Instant detectedAt) {

    public enum Status { OPEN, CLOSED }

    /** Same episode, same key: IMEI + type + start. */
    public String eventKey() {
        return imei + "|" + type + "|" + startedAt.toEpochMilli();
    }
}
