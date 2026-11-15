package com.srm.gps.application.geofence;

import com.srm.gps.connectivity.redis.LiveVehiclePositionStore;
import com.srm.gps.module.ModuleContext.VehicleRef;
import com.srm.gps.processing.geofence.GeofenceFinding;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table geofence_visit: one row per visit (or pass) of one vehicle to one fence. Every write is an upsert on the visit's
 * identity (device + fence + kind + entry), so a reading handled twice writes the same row.
 */
@Component
public class GeofenceVisitStore {

    private static final String UPSERT_ENTRY = """
            INSERT INTO geofence_visit (imei, vehicle_registration_no, tenant_code, place_id, place_code, place_name, place_role,
                kind, entered_at, entry_gap_s, entry_latitude, entry_longitude)
            VALUES (:imei, :vehicle, :tenant, :placeId, :code, :name, :role, 'VISIT', :enteredAt, :entryGap, :lat, :lon)
            ON CONFLICT (imei, place_id, kind, entered_at) DO UPDATE SET
                vehicle_registration_no = COALESCE(EXCLUDED.vehicle_registration_no, geofence_visit.vehicle_registration_no),
                tenant_code = COALESCE(EXCLUDED.tenant_code, geofence_visit.tenant_code),
                updated_at = now()
            """;

    /** The exit of a visit; when the entry row is missing (state lost, a replay that began inside) it is written whole. */
    private static final String UPSERT_EXIT = """
            INSERT INTO geofence_visit (imei, vehicle_registration_no, tenant_code, place_id, place_code, place_name, place_role,
                kind, entered_at, exited_at, exit_gap_s, entry_latitude, entry_longitude, exit_latitude, exit_longitude,
                dwell_alert_at, exit_reason)
            VALUES (:imei, :vehicle, :tenant, :placeId, :code, :name, :role, 'VISIT', :enteredAt, :exitedAt, :exitGap, :lat, :lon,
                :lat, :lon, :dwellAlertAt, :reason)
            ON CONFLICT (imei, place_id, kind, entered_at) DO UPDATE SET
                exited_at = EXCLUDED.exited_at, exit_gap_s = EXCLUDED.exit_gap_s, exit_latitude = EXCLUDED.exit_latitude,
                exit_longitude = EXCLUDED.exit_longitude, exit_reason = EXCLUDED.exit_reason,
                dwell_alert_at = COALESCE(geofence_visit.dwell_alert_at, EXCLUDED.dwell_alert_at),
                vehicle_registration_no = COALESCE(EXCLUDED.vehicle_registration_no, geofence_visit.vehicle_registration_no),
                tenant_code = COALESCE(EXCLUDED.tenant_code, geofence_visit.tenant_code),
                updated_at = now()
            """;

    private static final String UPSERT_PASS = """
            INSERT INTO geofence_visit (imei, vehicle_registration_no, tenant_code, place_id, place_code, place_name, place_role,
                kind, entered_at, exited_at, entry_gap_s, entry_latitude, entry_longitude, exit_latitude, exit_longitude, inferred)
            VALUES (:imei, :vehicle, :tenant, :placeId, :code, :name, :role, 'PASS', :enteredAt, :exitedAt, :entryGap, :lat, :lon,
                :lat, :lon, :inferred)
            ON CONFLICT (imei, place_id, kind, entered_at) DO UPDATE SET exited_at = EXCLUDED.exited_at, updated_at = now()
            """;

    private static final String DWELL_EXCEEDED = """
            UPDATE geofence_visit SET dwell_alert_at = :dwellAlertAt, dwell_limit_min = :limit, updated_at = now()
            WHERE imei = :imei AND place_id = :placeId AND kind = 'VISIT' AND entered_at = :enteredAt
            """;

    private static final String COLUMNS = """
            v.id, v.imei, v.vehicle_registration_no, v.tenant_code, v.place_id, v.place_code, v.place_name, v.place_role, v.kind,
            v.entered_at, v.exited_at, v.entry_gap_s, v.exit_gap_s, v.entry_latitude, v.entry_longitude, v.dwell_alert_at,
            v.dwell_limit_min, v.inferred, v.exit_reason
            """;

    /** A device silent this long is no longer "inside now" but "inside when last seen" (the portals' offline rule). */
    public static final long SILENT_AFTER_MINUTES = 30;

    private final NamedParameterJdbcTemplate jdbc;
    private final LiveVehiclePositionStore live;

    public GeofenceVisitStore(NamedParameterJdbcTemplate jdbc, LiveVehiclePositionStore live) {
        this.jdbc = jdbc;
        this.live = live;
    }

    /**
     * A visit row, as the API and the report read it.
     *
     * @param until          where the stay ends for counting: the exit, or for an open visit the device's last reading
     *                       (Redis live position) - not the wall clock, so a tracker that went silent inside a plant
     *                       does not keep adding hours, and a replay that lags real time reads right
     * @param silentMinutes  open visits: minutes since the device's last reading (0 when it reports now)
     * @param minutes        how long it lasted, entry to {@code until}
     */
    public record Visit(long id, String imei, String vehicleRegistrationNo, String tenantCode, long placeId, String placeCode,
                        String placeName, String placeRole, String kind, Instant enteredAt, Instant exitedAt, Long entryGapSeconds,
                        Long exitGapSeconds, double latitude, double longitude, Instant dwellAlertAt, Integer dwellLimitMinutes,
                        boolean inferred, String exitReason, Instant until, long silentMinutes, long minutes) {

        public boolean open() {
            return exitedAt == null;
        }

        /** Open, and the device has not reported for longer than {@link #SILENT_AFTER_MINUTES}: inside when last seen. */
        public boolean noSignal() {
            return open() && silentMinutes > SILENT_AFTER_MINUTES;
        }

        Visit endingAt(Instant end, Instant now) {
            Instant u = end.isAfter(now) ? now : end.isBefore(enteredAt) ? enteredAt : end;
            return new Visit(id, imei, vehicleRegistrationNo, tenantCode, placeId, placeCode, placeName, placeRole, kind, enteredAt,
                    exitedAt, entryGapSeconds, exitGapSeconds, latitude, longitude, dwellAlertAt, dwellLimitMinutes, inferred, exitReason,
                    u, Math.max(0, Duration.between(u, now).toMinutes()), Math.max(0, Duration.between(enteredAt, u).toMinutes()));
        }

        /** The largest gap around its two stamps: how far the times can be off. */
        public long uncertaintyMinutes() {
            return Math.max(entryGapSeconds == null ? 0 : entryGapSeconds, exitGapSeconds == null ? 0 : exitGapSeconds) / 60;
        }
    }

    /**
     * Which visits to read. Null fields do not filter; {@code vehicles} null = every vehicle, empty = none.
     */
    public record Filter(String imei, String vehicleRegistrationNo, Long placeId, String tenantCode, Collection<String> vehicles) {

        public static final Filter ALL = new Filter(null, null, null, null, null);
    }

    public void entered(VehicleRef vehicle, GeofenceFinding f) {
        jdbc.update(UPSERT_ENTRY, base(vehicle, f).addValue("enteredAt", ts(f.enteredAt())).addValue("entryGap", f.gapSeconds()));
    }

    public void exited(VehicleRef vehicle, GeofenceFinding f) {
        Instant enteredAt = f.enteredAt() != null ? f.enteredAt() : f.at();
        jdbc.update(UPSERT_EXIT, base(vehicle, f).addValue("enteredAt", ts(enteredAt)).addValue("exitedAt", ts(f.at()))
                .addValue("exitGap", f.gapSeconds()).addValue("dwellAlertAt", ts(f.dwellAlertAt())).addValue("reason", f.reason()));
    }

    public void passed(VehicleRef vehicle, GeofenceFinding f) {
        jdbc.update(UPSERT_PASS, base(vehicle, f).addValue("enteredAt", ts(f.at())).addValue("exitedAt", ts(f.endedAt()))
                .addValue("entryGap", f.gapSeconds()).addValue("inferred", f.inferred()));
    }

    public void dwellExceeded(VehicleRef vehicle, GeofenceFinding f) {
        jdbc.update(DWELL_EXCEEDED, new MapSqlParameterSource("imei", vehicle.imei()).addValue("placeId", f.fence().id())
                .addValue("enteredAt", ts(f.enteredAt())).addValue("dwellAlertAt", ts(f.at())).addValue("limit", f.dwellLimitMinutes()));
    }

    /**
     * Before a replay from {@code from}: visits that began since then are found again, so they go; a visit open across
     * that moment is cut there (exit_reason REPLAY_CUT) - the replay starts with no memory of it and opens a new one.
     */
    public int cutAt(Instant from) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", ts(from));
        int removed = jdbc.update("DELETE FROM geofence_visit WHERE entered_at >= :from", p);
        int cut = jdbc.update("""
                UPDATE geofence_visit SET exited_at = :from, exit_reason = 'REPLAY_CUT', updated_at = now()
                WHERE entered_at < :from AND (exited_at IS NULL OR exited_at > :from)
                """, p);
        return removed + cut;
    }

    /** Visits open now (vehicles inside a fence), longest first. */
    public List<Visit> open(Filter filter, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit).addValue("now", ts(Instant.now()));
        String where = where(filter, p);
        return lastSeen(jdbc.query("SELECT " + COLUMNS + " FROM geofence_visit v WHERE v.exited_at IS NULL AND v.kind = 'VISIT'"
                + where + " ORDER BY v.entered_at LIMIT :limit", p, this::map));
    }

    /** Visits and passes overlapping [from, to), oldest first. */
    public List<Visit> overlapping(Instant from, Instant to, Filter filter, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("from", ts(from)).addValue("to", ts(to)).addValue("limit", limit)
                .addValue("now", ts(Instant.now()));
        String where = where(filter, p);
        return lastSeen(jdbc.query("SELECT " + COLUMNS + " FROM geofence_visit v WHERE v.entered_at < :to"
                + " AND (v.exited_at IS NULL OR v.exited_at >= :from)" + where + " ORDER BY v.entered_at LIMIT :limit", p, this::map));
    }

    /** The newest visits and passes, newest first. */
    public List<Visit> recent(Filter filter, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit).addValue("now", ts(Instant.now()));
        String where = where(filter, p);
        return lastSeen(jdbc.query("SELECT " + COLUMNS + " FROM geofence_visit v WHERE true" + where
                + " ORDER BY greatest(v.entered_at, coalesce(v.exited_at, v.entered_at)) DESC LIMIT :limit", p, this::map));
    }

    /** Ends each open visit at its device's last reading (one Redis read per device); Redis down: at now. */
    private List<Visit> lastSeen(List<Visit> rows) {
        Instant now = Instant.now();
        Map<String, Instant> seen = new HashMap<>();
        List<Visit> out = new ArrayList<>(rows.size());
        for (Visit v : rows) {
            if (!v.open()) {
                out.add(v);
                continue;
            }
            Instant last = seen.computeIfAbsent(v.imei(), imei -> {
                try {
                    return live.findRecordedAt(imei).orElse(now);
                } catch (RuntimeException redisDown) {
                    return now;
                }
            });
            out.add(v.endingAt(last, now));
        }
        return out;
    }

    /** Vehicles inside each fence now: place id -> count. */
    public Map<Long, Integer> insideCounts() {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT place_id, count(DISTINCT imei) AS n FROM geofence_visit WHERE exited_at IS NULL AND kind = 'VISIT' GROUP BY place_id",
                rs -> {
                    counts.put(rs.getLong("place_id"), rs.getInt("n"));
                });
        return counts;
    }

    /**
     * What is usual at each fence: median and 90th percentile of closed visits since {@code since}, in minutes, with the
     * number of visits behind them. place id -> [visits, median, p90].
     */
    public Map<Long, double[]> typicalDwell(Instant since, Instant until) {
        Map<Long, double[]> typical = new LinkedHashMap<>();
        jdbc.query("""
                SELECT place_id, count(*) AS n,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM exited_at - entered_at) / 60) AS p50,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY extract(epoch FROM exited_at - entered_at) / 60) AS p90
                FROM geofence_visit
                WHERE kind = 'VISIT' AND exited_at IS NOT NULL AND exit_reason IS DISTINCT FROM 'REPLAY_CUT'
                  AND entered_at >= :since AND entered_at < :until
                GROUP BY place_id
                """, new MapSqlParameterSource("since", ts(since)).addValue("until", ts(until)), rs -> {
            typical.put(rs.getLong("place_id"), new double[]{rs.getLong("n"), rs.getDouble("p50"), rs.getDouble("p90")});
        });
        return typical;
    }

    private static String where(Filter f, MapSqlParameterSource p) {
        List<String> clauses = new ArrayList<>();
        if (f.imei() != null && !f.imei().isBlank()) {
            clauses.add("v.imei = :imei");
            p.addValue("imei", f.imei().trim());
        }
        if (f.vehicleRegistrationNo() != null && !f.vehicleRegistrationNo().isBlank()) {
            clauses.add("v.vehicle_registration_no = :vehicle");
            p.addValue("vehicle", f.vehicleRegistrationNo().trim());
        }
        if (f.placeId() != null) {
            clauses.add("v.place_id = :placeId");
            p.addValue("placeId", f.placeId());
        }
        if (f.tenantCode() != null && !f.tenantCode().isBlank()) {
            clauses.add("v.tenant_code = :tenant");
            p.addValue("tenant", f.tenantCode().trim());
        }
        if (f.vehicles() != null) {
            if (f.vehicles().isEmpty()) {
                clauses.add("false");
            } else {
                clauses.add("v.vehicle_registration_no IN (:vehicles)");
                p.addValue("vehicles", f.vehicles());
            }
        }
        return clauses.isEmpty() ? "" : " AND " + String.join(" AND ", clauses);
    }

    private Visit map(ResultSet rs, int row) throws SQLException {
        Instant entered = rs.getTimestamp("entered_at").toInstant();
        Timestamp exitedTs = rs.getTimestamp("exited_at");
        Instant exited = exitedTs == null ? null : exitedTs.toInstant();
        Timestamp alert = rs.getTimestamp("dwell_alert_at");
        Instant until = exited != null ? exited : Instant.now();
        long minutes = Math.max(0, Duration.between(entered, until).toMinutes());
        return new Visit(rs.getLong("id"), rs.getString("imei"), rs.getString("vehicle_registration_no"), rs.getString("tenant_code"),
                rs.getLong("place_id"), rs.getString("place_code"), rs.getString("place_name"), rs.getString("place_role"),
                rs.getString("kind"), entered, exited, (Long) rs.getObject("entry_gap_s"), (Long) rs.getObject("exit_gap_s"),
                rs.getDouble("entry_latitude"), rs.getDouble("entry_longitude"), alert == null ? null : alert.toInstant(),
                (Integer) rs.getObject("dwell_limit_min"), rs.getBoolean("inferred"), rs.getString("exit_reason"), until, 0, minutes);
    }

    private static MapSqlParameterSource base(VehicleRef vehicle, GeofenceFinding f) {
        return new MapSqlParameterSource("imei", vehicle.imei())
                .addValue("vehicle", vehicle.registrationNo())
                .addValue("tenant", vehicle.tenantCode())
                .addValue("placeId", f.fence().id())
                .addValue("code", f.fence().code())
                .addValue("name", f.fence().name())
                .addValue("role", f.fence().role())
                .addValue("lat", f.latitude())
                .addValue("lon", f.longitude());
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}
