package com.srm.gps.application.route;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.route.RoutePlan;
import com.srm.gps.processing.route.RouteTrackState;
import com.srm.gps.processing.route.WaypointTracker;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tables route_plan and route_stop (V11): the plans, their stops, and the live numbers the modules write on changes. */
@Repository
public class RoutePlanStore {

    public record Plan(long id, Long shipmentId, String imei, String vehicleNo, String tenantCode, String source, String status,
                       String originName, Long originPlaceId, double originLat, double originLon, String destinationName,
                       Long destinationPlaceId, double destinationLat, double destinationLon, double destinationRadius, String engine,
                       String costing, int version, String reason, String shape, String timings, double lengthMeters,
                       double plannedSeconds, double offsetMeters, String firstShape, double firstLengthMeters, double firstPlannedSeconds,
                       double initialPace, Instant plannedStartAt, String routeStatus, Instant departedAt, Instant promisedEtaAt,
                       Instant etaAt, Instant arrivedAt, Double pace, Double chainage, Double crossTrack, Instant lastReadingAt,
                       Double lastLat, Double lastLon, int deviations, double deviationKm, int deviationMinutes, int wrongWays,
                       int alternates, String createdBy, Instant createdAt, String tripNo, String consignee) {
    }

    public record Stop(long id, long planId, int seq, String kind, Long placeId, String name, double latitude, double longitude,
                       double radius, double chainage, int stayMinutes, String status, Instant arrivedAt, Instant departedAt,
                       Instant missedAt, Integer minutesInside, boolean outOfOrder) {
    }

    /** A shipment that needs a plan: open, with an origin and a destination geofence, and none yet. */
    public record ShipmentToPlan(long id, String tripNo, String vehicleNo, String imei, String tenantCode, long originPlaceId,
                                 long destinationPlaceId, Instant dispatchedAt) {
    }

    /** What a new plan is made of (the engine's line, its stops, where it starts). */
    public record NewPlan(Long shipmentId, String imei, String vehicleNo, String tenantCode, String source, String originName,
                          Long originPlaceId, double originLat, double originLon, String destinationName, Long destinationPlaceId,
                          double destinationLat, double destinationLon, double destinationRadius, String engine, String costing,
                          String reason, String shape, List<RoutePlan.Timing> timings, double lengthMeters, double plannedSeconds,
                          double initialPace, Instant plannedStartAt, String createdBy) {
    }

    private static final String SELECT = """
            SELECT p.*, s.trip_no, s.consignee_name
            FROM route_plan p LEFT JOIN shipment s ON s.id = p.shipment_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RoutePlanStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(NewPlan p, List<RoutePlan.Stop> stops) {
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("shipment", p.shipmentId()).addValue("imei", p.imei()).addValue("vehicle", p.vehicleNo())
                .addValue("tenant", p.tenantCode()).addValue("source", p.source())
                .addValue("originName", p.originName()).addValue("originPlace", p.originPlaceId())
                .addValue("originLat", p.originLat()).addValue("originLon", p.originLon())
                .addValue("destinationName", p.destinationName()).addValue("destinationPlace", p.destinationPlaceId())
                .addValue("destinationLat", p.destinationLat()).addValue("destinationLon", p.destinationLon())
                .addValue("radius", p.destinationRadius()).addValue("engine", p.engine()).addValue("costing", p.costing())
                .addValue("reason", p.reason()).addValue("shape", p.shape()).addValue("timings", timingsJson(p.timings()))
                .addValue("length", p.lengthMeters()).addValue("planned", p.plannedSeconds()).addValue("pace", p.initialPace())
                .addValue("start", Timestamp.from(p.plannedStartAt())).addValue("by", p.createdBy());
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO route_plan (shipment_id, imei, vehicle_registration_no, tenant_code, source, origin_name, origin_place_id,
                    origin_lat, origin_lon, destination_name, destination_place_id, destination_lat, destination_lon,
                    destination_radius_m, engine, costing, reason, shape, timings, length_m, planned_s, first_shape, first_length_m,
                    first_planned_s, initial_pace, planned_start_at, created_by)
                VALUES (:shipment, :imei, :vehicle, :tenant, :source, :originName, :originPlace, :originLat, :originLon,
                    :destinationName, :destinationPlace, :destinationLat, :destinationLon, :radius, :engine, :costing, :reason,
                    :shape, CAST(:timings AS jsonb), :length, :planned, :shape, :length, :planned, :pace, :start, :by)
                """, args, key, new String[] {"id"});
        long id = key.getKey().longValue();
        insertStops(id, stops);
        return id;
    }

    public void insertStops(long planId, List<RoutePlan.Stop> stops) {
        for (RoutePlan.Stop s : stops) {
            jdbc.update("""
                    INSERT INTO route_stop (plan_id, seq, kind, place_id, name, latitude, longitude, radius_m, chainage_m, stay_min)
                    VALUES (:plan, :seq, :kind, :place, :name, :lat, :lon, :radius, :chainage, :stay)
                    """, new MapSqlParameterSource().addValue("plan", planId).addValue("seq", s.seq()).addValue("kind", s.kind())
                    .addValue("place", s.placeId()).addValue("name", s.name()).addValue("lat", s.area().latitude())
                    .addValue("lon", s.area().longitude()).addValue("radius", s.area().radiusMeters())
                    .addValue("chainage", s.chainageMeters()).addValue("stay", s.stayMinutes()));
        }
    }

    /** Closes the truck's other active plans: one truck follows one plan. */
    public int closeOthers(String vehicleNo, String imei, long exceptId) {
        return jdbc.update("""
                UPDATE route_plan SET status = 'CLOSED', updated_at = now()
                WHERE status = 'ACTIVE' AND id <> :id
                  AND (vehicle_registration_no = :vehicle OR (CAST(:imei AS varchar) IS NOT NULL AND imei = CAST(:imei AS varchar)))
                """, new MapSqlParameterSource("id", exceptId).addValue("vehicle", vehicleNo).addValue("imei", imei));
    }

    /** A re-plan became the plan (ROUTE_ALTERNATE): the new line, its offset, its stops' places along it. */
    public void replaceLine(long planId, int version, String shape, List<RoutePlan.Timing> timings, double lengthMeters,
                            double plannedSeconds, double offsetMeters, String engine, List<RoutePlan.Stop> keptStops,
                            List<RoutePlan.Stop> newStops) {
        jdbc.update("""
                UPDATE route_plan SET version = :version, reason = 'ALTERNATE', shape = :shape, timings = CAST(:timings AS jsonb),
                    length_m = :length, planned_s = :planned, offset_m = :offset, engine = :engine, updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("id", planId).addValue("version", version).addValue("shape", shape)
                .addValue("timings", timingsJson(timings)).addValue("length", lengthMeters).addValue("planned", plannedSeconds)
                .addValue("offset", offsetMeters).addValue("engine", engine));
        jdbc.update("DELETE FROM route_stop WHERE plan_id = :id AND kind = 'FENCE' AND status = 'PENDING'",
                new MapSqlParameterSource("id", planId));
        for (RoutePlan.Stop s : keptStops) {
            jdbc.update("UPDATE route_stop SET chainage_m = :chainage, updated_at = now() WHERE plan_id = :id AND seq = :seq",
                    new MapSqlParameterSource("id", planId).addValue("seq", s.seq()).addValue("chainage", s.chainageMeters()));
        }
        insertStops(planId, newStops);
    }

    public List<Plan> active() {
        return jdbc.query(SELECT + " WHERE p.status = 'ACTIVE' ORDER BY p.id", new MapSqlParameterSource(), PLAN);
    }

    /** Changes when a plan is added, closed or re-planned (not on live numbers): the directory reloads on it. */
    public String fingerprint() {
        return jdbc.queryForObject("""
                SELECT count(*) || '|' || coalesce(max(id), 0) || '|' || coalesce(sum(version), 0)
                FROM route_plan WHERE status = 'ACTIVE'
                """, new MapSqlParameterSource(), String.class);
    }

    public Optional<Plan> byId(long id) {
        return jdbc.query(SELECT + " WHERE p.id = :id", new MapSqlParameterSource("id", id), PLAN).stream().findFirst();
    }

    /** Plans active now, or that started or ended in the window, newest first. */
    public List<Plan> recent(Instant from, Collection<String> vehicles, int limit) {
        MapSqlParameterSource args = new MapSqlParameterSource("from", Timestamp.from(from)).addValue("limit", limit);
        String scope = "";
        if (vehicles != null) {
            if (vehicles.isEmpty()) {
                return List.of();
            }
            scope = " AND p.vehicle_registration_no IN (:vehicles)";
            args.addValue("vehicles", vehicles);
        }
        return jdbc.query(SELECT + """
                 WHERE (p.status = 'ACTIVE' OR p.planned_start_at >= :from OR p.arrived_at >= :from OR p.updated_at >= :from)
                """ + scope + " ORDER BY (p.status = 'ACTIVE') DESC, p.planned_start_at DESC LIMIT :limit", args, PLAN);
    }

    public List<Stop> stops(long planId) {
        return jdbc.query("SELECT * FROM route_stop WHERE plan_id = :id ORDER BY seq", new MapSqlParameterSource("id", planId), STOP);
    }

    public Map<Long, List<Stop>> stops(Collection<Long> planIds) {
        Map<Long, List<Stop>> byPlan = new LinkedHashMap<>();
        if (planIds.isEmpty()) {
            return byPlan;
        }
        jdbc.query("SELECT * FROM route_stop WHERE plan_id IN (:ids) ORDER BY plan_id, seq", new MapSqlParameterSource("ids", planIds),
                rs -> {
                    Stop s = STOP.mapRow(rs, 0);
                    byPlan.computeIfAbsent(s.planId(), k -> new ArrayList<>()).add(s);
                });
        return byPlan;
    }

    /** The live numbers of a plan (route-deviation module, on status changes, findings and every few minutes). */
    public void saveLive(long planId, RouteTrackState s) {
        jdbc.update("""
                UPDATE route_plan SET route_status = :status, departed_at = :departed, promised_eta_at = :promised, eta_at = :eta,
                    pace = :pace, chainage_m = :chainage, cross_track_m = :cross, last_reading_at = :at, last_lat = :lat,
                    last_lon = :lon, deviations = :deviations, deviation_km = :deviationKm, deviation_minutes = :deviationMinutes,
                    wrong_ways = :wrongWays, alternates = :alternates, updated_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource("id", planId).addValue("status", s.status.name())
                .addValue("departed", ts(s.departedAt)).addValue("promised", ts(s.etaPromised)).addValue("eta", ts(s.etaShown))
                .addValue("pace", s.pace).addValue("chainage", s.chainage).addValue("cross", s.crossTrack)
                .addValue("at", ts(s.lastAt)).addValue("lat", s.lastLatitude).addValue("lon", s.lastLongitude)
                .addValue("deviations", s.deviations).addValue("deviationKm", s.deviationMeters / 1000)
                .addValue("deviationMinutes", (int) (s.deviationSeconds / 60)).addValue("wrongWays", s.wrongWays)
                .addValue("alternates", s.alternates));
    }

    public void arrived(long planId, Instant at) {
        jdbc.update("UPDATE route_plan SET status = 'ARRIVED', route_status = 'ARRIVED', arrived_at = :at, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", planId).addValue("at", Timestamp.from(at)));
    }

    public int cancel(long planId) {
        return jdbc.update("UPDATE route_plan SET status = 'CANCELLED', updated_at = now() WHERE id = :id AND status = 'ACTIVE'",
                new MapSqlParameterSource("id", planId));
    }

    /** A waypoint's new status (waypoints module). */
    public void stopChanged(long planId, int seq, WaypointTracker.StopState st) {
        Integer minutes = st.firstInsideAt() != null && st.lastInsideAt() != null
                ? (int) java.time.Duration.between(st.firstInsideAt(), st.lastInsideAt()).toMinutes() : null;
        boolean arrived = st.status() == WaypointTracker.Status.ARRIVED || st.status() == WaypointTracker.Status.DEPARTED;
        jdbc.update("""
                UPDATE route_stop SET status = :status, arrived_at = :arrived, departed_at = :departed,
                    missed_at = CASE WHEN :status = 'MISSED' THEN coalesce(missed_at, now()) END,
                    minutes_inside = :minutes, out_of_order = :ooo, updated_at = now()
                WHERE plan_id = :plan AND seq = :seq
                """, new MapSqlParameterSource("plan", planId).addValue("seq", seq).addValue("status", st.status().name())
                .addValue("arrived", arrived ? ts(st.firstInsideAt()) : null)
                .addValue("departed", st.status() == WaypointTracker.Status.DEPARTED ? ts(st.lastInsideAt()) : null)
                .addValue("minutes", minutes).addValue("ooo", st.outOfOrder()));
    }

    /** Open shipments with both geofences and no plan yet, oldest first. */
    public List<ShipmentToPlan> shipmentsToPlan(int limit) {
        return jdbc.query("""
                SELECT s.id, s.trip_no, s.vehicle_registration_no, coalesce(m.imei, s.device_imei) AS imei, vt.code AS tenant_code,
                       s.origin_place_id, s.destination_place_id, s.dispatched_at
                FROM shipment s
                LEFT JOIN vehicle v ON v.registration_no = s.vehicle_registration_no
                LEFT JOIN tenant vt ON vt.id = v.owner_tenant_id
                LEFT JOIN LATERAL (
                    SELECT d.imei FROM device_assignment a JOIN device d ON d.id = a.device_id
                    WHERE a.vehicle_id = v.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now()) LIMIT 1) m ON true
                WHERE s.arrived_at IS NULL AND s.closed_at IS NULL
                  AND s.origin_place_id IS NOT NULL AND s.destination_place_id IS NOT NULL
                  AND s.origin_place_id <> s.destination_place_id
                  AND s.dispatched_at > now() - interval '7 days' AND s.dispatched_at <= now() + interval '1 day'
                  AND NOT EXISTS (SELECT 1 FROM route_plan p WHERE p.shipment_id = s.id)
                ORDER BY s.dispatched_at
                LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), (rs, n) -> new ShipmentToPlan(rs.getLong("id"), rs.getString("trip_no"),
                rs.getString("vehicle_registration_no"), rs.getString("imei"), rs.getString("tenant_code"), rs.getLong("origin_place_id"),
                rs.getLong("destination_place_id"), rs.getTimestamp("dispatched_at").toInstant()));
    }

    /** The device in the vehicle now. */
    public Optional<String> deviceOf(String vehicleNo) {
        return jdbc.queryForList("""
                SELECT d.imei FROM vehicle v
                JOIN device_assignment a ON a.vehicle_id = v.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                JOIN device d ON d.id = a.device_id
                WHERE v.registration_no = :vehicle LIMIT 1
                """, new MapSqlParameterSource("vehicle", vehicleNo.trim().toUpperCase()), String.class).stream().findFirst();
    }

    public Optional<String> tenantOf(String vehicleNo) {
        return jdbc.queryForList("""
                SELECT t.code FROM vehicle v JOIN tenant t ON t.id = v.owner_tenant_id WHERE v.registration_no = :vehicle
                """, new MapSqlParameterSource("vehicle", vehicleNo.trim().toUpperCase()), String.class).stream().findFirst();
    }

    /** Vehicles with a device now, for choosing whom to plan for. */
    public List<Map<String, Object>> vehiclesWithDevices(Collection<String> scope) {
        MapSqlParameterSource args = new MapSqlParameterSource();
        String where = "";
        if (scope != null) {
            if (scope.isEmpty()) {
                return List.of();
            }
            where = " WHERE v.registration_no IN (:vehicles)";
            args.addValue("vehicles", scope);
        }
        return jdbc.queryForList("""
                SELECT v.registration_no AS vehicle, d.imei, t.code AS tenant,
                       (SELECT p.id FROM route_plan p WHERE p.status = 'ACTIVE' AND p.vehicle_registration_no = v.registration_no
                        LIMIT 1) AS active_plan_id
                FROM vehicle v
                JOIN device_assignment a ON a.vehicle_id = v.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                JOIN device d ON d.id = a.device_id
                LEFT JOIN tenant t ON t.id = v.owner_tenant_id
                """ + where + " ORDER BY v.registration_no", args);
    }

    /** Active plans whose shipment closed without the truck arriving: CLOSED. */
    public int closeFinishedShipments() {
        return jdbc.update("""
                UPDATE route_plan p SET status = 'CLOSED', updated_at = now()
                FROM shipment s
                WHERE p.shipment_id = s.id AND p.status = 'ACTIVE' AND s.closed_at IS NOT NULL
                """, new MapSqlParameterSource());
    }

    /** The lane's median pace over at least 3 arrived trips between the same two geofences. */
    public Optional<Double> lanePace(Long originPlaceId, Long destinationPlaceId) {
        if (originPlaceId == null || destinationPlaceId == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY pace) AS pace, count(*) AS n
                FROM route_plan
                WHERE status = 'ARRIVED' AND origin_place_id = :o AND destination_place_id = :d AND pace IS NOT NULL
                """, new MapSqlParameterSource("o", originPlaceId).addValue("d", destinationPlaceId),
                (rs, n) -> rs.getInt("n") >= 3 ? rs.getDouble("pace") : null).stream().filter(v -> v != null).findFirst();
    }

    /** Replay from a moment: plans that were active then go back to WAITING, their stops to PENDING. */
    public int resetSince(Instant from) {
        MapSqlParameterSource args = new MapSqlParameterSource("from", Timestamp.from(from));
        jdbc.update("""
                UPDATE route_stop SET status = 'PENDING', arrived_at = NULL, departed_at = NULL, missed_at = NULL, minutes_inside = NULL,
                    out_of_order = false, updated_at = now()
                WHERE plan_id IN (SELECT id FROM route_plan WHERE status = 'ACTIVE' OR arrived_at >= :from)
                """, args);
        return jdbc.update("""
                UPDATE route_plan SET status = 'ACTIVE', route_status = 'WAITING', departed_at = NULL, promised_eta_at = NULL,
                    eta_at = NULL, arrived_at = NULL, deviations = 0, deviation_km = 0, deviation_minutes = 0, wrong_ways = 0,
                    alternates = 0, updated_at = now()
                WHERE status = 'ACTIVE' OR arrived_at >= :from
                """, args);
    }

    static String timingsJson(List<RoutePlan.Timing> timings) {
        List<double[]> rows = new ArrayList<>();
        for (RoutePlan.Timing t : timings) {
            rows.add(new double[] {t.fromIndex(), t.toIndex(), Math.round(t.seconds() * 10) / 10.0});
        }
        return GpsMessageJson.toJson(rows);
    }

    static List<RoutePlan.Timing> timings(String json) {
        List<RoutePlan.Timing> timings = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return timings;
        }
        for (Object row : GpsMessageJson.fromJson(json, List.class)) {
            if (row instanceof List<?> r && r.size() == 3) {
                timings.add(new RoutePlan.Timing(((Number) r.get(0)).intValue(), ((Number) r.get(1)).intValue(),
                        ((Number) r.get(2)).doubleValue()));
            }
        }
        return timings;
    }

    private static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<Plan> PLAN = (rs, n) -> new Plan(rs.getLong("id"), nullableLong(rs, "shipment_id"),
            rs.getString("imei"), rs.getString("vehicle_registration_no"), rs.getString("tenant_code"), rs.getString("source"),
            rs.getString("status"), rs.getString("origin_name"), nullableLong(rs, "origin_place_id"), rs.getDouble("origin_lat"),
            rs.getDouble("origin_lon"), rs.getString("destination_name"), nullableLong(rs, "destination_place_id"),
            rs.getDouble("destination_lat"), rs.getDouble("destination_lon"), rs.getDouble("destination_radius_m"),
            rs.getString("engine"), rs.getString("costing"), rs.getInt("version"), rs.getString("reason"), rs.getString("shape"),
            rs.getString("timings"), rs.getDouble("length_m"), rs.getDouble("planned_s"), rs.getDouble("offset_m"),
            rs.getString("first_shape"), rs.getDouble("first_length_m"), rs.getDouble("first_planned_s"), rs.getDouble("initial_pace"),
            instant(rs, "planned_start_at"), rs.getString("route_status"), instant(rs, "departed_at"), instant(rs, "promised_eta_at"),
            instant(rs, "eta_at"), instant(rs, "arrived_at"), nullableDouble(rs, "pace"), nullableDouble(rs, "chainage_m"),
            nullableDouble(rs, "cross_track_m"), instant(rs, "last_reading_at"), nullableDouble(rs, "last_lat"),
            nullableDouble(rs, "last_lon"), rs.getInt("deviations"), rs.getDouble("deviation_km"), rs.getInt("deviation_minutes"),
            rs.getInt("wrong_ways"), rs.getInt("alternates"), rs.getString("created_by"), instant(rs, "created_at"),
            rs.getString("trip_no"), rs.getString("consignee_name"));

    private static final RowMapper<Stop> STOP = (rs, n) -> new Stop(rs.getLong("id"), rs.getLong("plan_id"), rs.getInt("seq"),
            rs.getString("kind"), nullableLong(rs, "place_id"), rs.getString("name"), rs.getDouble("latitude"), rs.getDouble("longitude"),
            rs.getDouble("radius_m"), rs.getDouble("chainage_m"), rs.getInt("stay_min"), rs.getString("status"),
            instant(rs, "arrived_at"), instant(rs, "departed_at"), instant(rs, "missed_at"),
            rs.getObject("minutes_inside") == null ? null : rs.getInt("minutes_inside"), rs.getBoolean("out_of_order"));
}
