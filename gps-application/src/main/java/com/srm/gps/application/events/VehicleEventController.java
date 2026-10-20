package com.srm.gps.application.events;

import com.srm.gps.configuration.json.GpsMessageJson;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only API over the vehicle events the stream consumers raised.
 *
 *   GET /api/events?type=OVERSPEED&imei=...&status=OPEN&limit=100   newest first (by last update)
 *   GET /api/events/summary                                          counts per type, last 24 hours, and open now
 */
@RestController
public class VehicleEventController {

    private static final int MAXIMUM_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbc;

    public VehicleEventController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EventView(long id, String imei, String vehicleRegistrationNo, String tenantCode, String type,
                            String severity, String status, Instant startedAt, Instant endedAt, double latitude,
                            double longitude, String detectedBy, Map<String, Object> details, Instant updatedAt) {
    }

    public record TypeCount(String type, long last24Hours, long openNow) {
    }

    @GetMapping("/api/events")
    public List<EventView> events(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) String imei,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "100") int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM vehicle_event WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource("limit", Math.clamp(limit, 1, MAXIMUM_LIMIT));
        if (type != null && !type.isBlank()) {
            sql.append(" AND event_type IN (:types)");
            params.addValue("types", List.of(type.toUpperCase().split(",")));
        }
        if (imei != null && !imei.isBlank()) {
            sql.append(" AND imei = :imei");
            params.addValue("imei", imei.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status.toUpperCase());
        }
        sql.append(" ORDER BY updated_at DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, (row, i) -> toView(row));
    }

    @GetMapping("/api/events/summary")
    public List<TypeCount> summary() {
        return jdbc.query("""
                SELECT event_type,
                       COUNT(*) FILTER (WHERE started_at > now() - interval '24 hours') AS last_24h,
                       COUNT(*) FILTER (WHERE status = 'OPEN') AS open_now
                FROM vehicle_event GROUP BY event_type ORDER BY event_type
                """, new MapSqlParameterSource(),
                (row, i) -> new TypeCount(row.getString("event_type"), row.getLong("last_24h"), row.getLong("open_now")));
    }

    @SuppressWarnings("unchecked")
    private static EventView toView(ResultSet row) throws SQLException {
        String details = row.getString("details");
        return new EventView(
                row.getLong("id"),
                row.getString("imei"),
                row.getString("vehicle_registration_no"),
                row.getString("tenant_code"),
                row.getString("event_type"),
                row.getString("severity"),
                row.getString("status"),
                instant(row.getTimestamp("started_at")),
                instant(row.getTimestamp("ended_at")),
                row.getDouble("latitude"),
                row.getDouble("longitude"),
                row.getString("detected_by"),
                details == null ? Map.of() : GpsMessageJson.fromJson(details, LinkedHashMap.class),
                instant(row.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
