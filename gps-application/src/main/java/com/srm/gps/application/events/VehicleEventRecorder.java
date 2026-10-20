package com.srm.gps.application.events;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.connectivity.kafka.VehicleEventPublisher;
import com.srm.gps.connectivity.redis.RedisStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Records a vehicle event in three places:
 *   1. PostgreSQL vehicle_event, upserted by event key (an episode's start and end are the same row),
 *   2. Redis gps:events:{imei}, the vehicle's latest events for the tracking window,
 *   3. Kafka gps.vehicle-events (Kafka flow), for downstream dispatch and notifications.
 * Details are merged (jsonb ||), so context added later by the agents survives the episode's end.
 */
@Component
public class VehicleEventRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleEventRecorder.class);
    private static final int EVENTS_KEPT_PER_VEHICLE = 50;

    private static final String UPSERT = """
            INSERT INTO vehicle_event (event_key, imei, tenant_code, vehicle_registration_no, event_type, severity, status,
                started_at, ended_at, latitude, longitude, detected_by, details, updated_at)
            VALUES (:eventKey, :imei, :tenantCode, :vehicleRegistrationNo, :type, :severity, :status,
                :startedAt, :endedAt, :latitude, :longitude, :detectedBy, CAST(:details AS jsonb), now())
            ON CONFLICT (event_key) DO UPDATE SET
                tenant_code = COALESCE(EXCLUDED.tenant_code, vehicle_event.tenant_code),
                vehicle_registration_no = COALESCE(EXCLUDED.vehicle_registration_no, vehicle_event.vehicle_registration_no),
                severity = CASE WHEN jsonb_exists(vehicle_event.details, 'contextVerdict')
                                THEN vehicle_event.severity ELSE EXCLUDED.severity END,
                status = EXCLUDED.status,
                ended_at = EXCLUDED.ended_at,
                details = vehicle_event.details || EXCLUDED.details,
                updated_at = now()
            """;

    private static final String MERGE_DETAILS = """
            UPDATE vehicle_event SET details = details || CAST(:details AS jsonb), severity = :severity, updated_at = now()
            WHERE event_key = :eventKey
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RedisStateStore redis;
    private final ObjectProvider<VehicleEventPublisher> publisher;

    public VehicleEventRecorder(NamedParameterJdbcTemplate jdbc, RedisStateStore redis, ObjectProvider<VehicleEventPublisher> publisher) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.publisher = publisher;
    }

    public void record(VehicleEvent event) {
        jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("eventKey", event.eventKey())
                .addValue("imei", event.imei())
                .addValue("tenantCode", event.tenantCode())
                .addValue("vehicleRegistrationNo", event.vehicleRegistrationNo())
                .addValue("type", event.type().name())
                .addValue("severity", event.severity().name())
                .addValue("status", event.status().name())
                .addValue("startedAt", Timestamp.from(event.startedAt()))
                .addValue("endedAt", event.endedAt() == null ? null : Timestamp.from(event.endedAt()))
                .addValue("latitude", event.latitude())
                .addValue("longitude", event.longitude())
                .addValue("detectedBy", event.detectedBy())
                .addValue("details", GpsMessageJson.toJson(event.details())));
        try {
            redis.pushEvent(event.imei(), GpsMessageJson.toJson(event), EVENTS_KEPT_PER_VEHICLE);
        } catch (RuntimeException redisProblem) {
            LOG.warn("Event {} stored, but not added to the vehicle's Redis list: {}", event.eventKey(), redisProblem.getMessage());
        }
        publisher.ifAvailable(p -> p.publish(event));
        LOG.info("{} {} {} ({}) at {}", event.type(), event.status(), event.vehicleRegistrationNo() != null
                ? event.vehicleRegistrationNo() : event.imei(), event.severity(), event.startedAt());
    }

    /** Adds context (for example the traffic/weather verdict) to an event that is already recorded. */
    public void mergeDetails(String eventKey, Map<String, Object> details, EventSeverity severity) {
        jdbc.update(MERGE_DETAILS, new MapSqlParameterSource()
                .addValue("eventKey", eventKey)
                .addValue("details", GpsMessageJson.toJson(details))
                .addValue("severity", severity.name()));
    }
}
