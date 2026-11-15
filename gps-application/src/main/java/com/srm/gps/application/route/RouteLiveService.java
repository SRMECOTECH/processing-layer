package com.srm.gps.application.route;

import com.srm.gps.application.modules.ModuleRunner;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.route.Polyline6;
import com.srm.gps.processing.route.RoutePlan;
import com.srm.gps.processing.route.RouteSettings;
import com.srm.gps.processing.route.RouteTrackState;
import com.srm.gps.processing.route.RouteTracker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the route pages show: one ETA strip per trip (progress along the plan, state, ETA against the promise, the
 * stops with their own ETAs) and, for one trip, the map (planned line, the original plan after a re-plan, the real trail
 * marked on/off the line, the events).
 *
 * Live numbers come from the module's Redis state (fresh at every reading), the plan row when there is none. A stop's
 * ETA uses the same pace as the trip: planned seconds from here to the stop x pace + the planned stays before it.
 */
@Service
public class RouteLiveService {

    private static final int MAP_POINTS = 1_500;
    private static final long SILENT_AFTER_MINUTES = 30;
    private static final List<String> ROUTE_EVENTS = List.of("ROUTE_DEVIATION", "ROUTE_ALTERNATE", "WRONG_DIRECTION", "ETA_CHANGED",
            "DESTINATION_REACHED", "WAYPOINT_ARRIVED", "WAYPOINT_DEPARTED", "WAYPOINT_MISSED");

    private final RoutePlanStore store;
    private final RoutePlanDirectory directory;
    private final StringRedisTemplate redis;
    private final NamedParameterJdbcTemplate jdbc;
    private final RouteSettings settings;

    public RouteLiveService(RoutePlanStore store, RoutePlanDirectory directory, StringRedisTemplate redis, NamedParameterJdbcTemplate jdbc,
                            RouteSettings settings) {
        this.store = store;
        this.directory = directory;
        this.redis = redis;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** @param vehicles the trucks the user may see, null for everyone */
    public Map<String, Object> live(Collection<String> vehicles, int hours) {
        Instant now = Instant.now();
        List<RoutePlanStore.Plan> plans = store.recent(now.minus(Duration.ofHours(Math.max(1, hours))), vehicles, 400);
        Map<Long, List<RoutePlanStore.Stop>> stops = store.stops(plans.stream().map(RoutePlanStore.Plan::id).toList());
        List<Map<String, Object>> trips = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("WAITING", "ON_ROUTE", "SUSPECT", "OFF_ROUTE", "WRONG_WAY", "ARRIVED", "LATE", "NO_SIGNAL")) {
            counts.put(s, 0);
        }
        long lateSum = 0;
        int lateN = 0;
        for (RoutePlanStore.Plan p : plans) {
            Map<String, Object> trip = trip(p, stops.getOrDefault(p.id(), List.of()), now, false);
            trips.add(trip);
            if ("ACTIVE".equals(p.status()) || "ARRIVED".equals(p.status())) {
                counts.merge((String) trip.get("routeStatus"), 1, Integer::sum);
                if (Boolean.TRUE.equals(trip.get("wrongWay"))) {
                    counts.merge("WRONG_WAY", 1, Integer::sum);
                }
                if (trip.get("lateMinutes") instanceof Long late) {
                    lateSum += late;
                    lateN++;
                    if (late >= 30) {
                        counts.merge("LATE", 1, Integer::sum);
                    }
                }
                if (Boolean.TRUE.equals(trip.get("noSignal"))) {
                    counts.merge("NO_SIGNAL", 1, Integer::sum);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now.toString());
        out.put("counts", counts);
        out.put("averageLateMinutes", lateN == 0 ? null : lateSum / lateN);
        out.put("trips", trips);
        return out;
    }

    public Map<String, Object> detail(long id) {
        RoutePlanStore.Plan p = store.byId(id).orElseThrow(() -> new IllegalArgumentException("No route plan " + id));
        List<RoutePlanStore.Stop> stops = store.stops(id);
        Instant now = Instant.now();
        Map<String, Object> out = trip(p, stops, now, true);
        RoutePlan plan = plan(p, stops);
        out.put("line", plan.shape(MAP_POINTS));
        if (p.version() > 1) {
            out.put("firstLine", RoutePlan.of(0, 1, Polyline6.decode(p.firstShape()), List.of(), p.firstPlannedSeconds(), List.of(), null,
                    plan.destination(), 0, 1, settings).shape(MAP_POINTS));
        }
        Instant from = p.plannedStartAt().minus(Duration.ofHours(1));
        Instant to = p.arrivedAt() != null ? p.arrivedAt().plus(Duration.ofMinutes(30)) : now;
        out.put("trail", trail(p, plan, from, to));
        out.put("events", events(p, from));
        return out;
    }

    private Map<String, Object> trip(RoutePlanStore.Plan p, List<RoutePlanStore.Stop> stopRows, Instant now, boolean full) {
        RouteTrackState s = "ACTIVE".equals(p.status()) ? state(p) : null;
        RoutePlan plan = null;
        RoutePlanDirectory.Entry entry = "ACTIVE".equals(p.status()) ? directory.byId(p.id()) : null;
        if (entry != null && entry.plan().version() == p.version()) {
            plan = entry.plan();
        } else if (full || s != null) {
            plan = plan(p, stopRows);
        }
        String status = s != null ? s.status.name() : p.routeStatus();
        double chainage = s != null ? s.chainage : p.chainage() == null ? 0 : p.chainage();
        double pace = s != null ? RouteTracker.effectivePace(s, p.initialPace(), settings) : p.pace() == null ? p.initialPace() : p.pace();
        Instant eta = s != null ? s.etaShown : p.etaAt();
        Instant promised = s != null ? s.etaPromised : p.promisedEtaAt();
        Instant lastAt = s != null ? s.lastAt : p.lastReadingAt();
        double total = p.offsetMeters() + p.lengthMeters();
        double done = "ARRIVED".equals(status) ? total : Math.min(total, p.offsetMeters() + chainage);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("planId", p.id());
        t.put("status", p.status());
        t.put("routeStatus", status);
        t.put("vehicle", p.vehicleNo());
        t.put("imei", p.imei());
        t.put("tenant", p.tenantCode());
        t.put("source", p.source());
        t.put("shipmentId", p.shipmentId());
        t.put("tripNo", p.tripNo());
        t.put("consignee", p.consignee());
        t.put("origin", p.originName());
        t.put("destination", p.destinationName());
        t.put("originLat", p.originLat());
        t.put("originLon", p.originLon());
        t.put("destinationLat", p.destinationLat());
        t.put("destinationLon", p.destinationLon());
        t.put("engine", p.engine());
        t.put("version", p.version());
        t.put("plannedStartAt", text(p.plannedStartAt()));
        t.put("plannedKm", round1(total / 1000));
        t.put("plannedMinutes", Math.round((p.offsetMeters() > 0 ? p.firstPlannedSeconds() : p.plannedSeconds()) / 60));
        t.put("doneKm", round1(done / 1000));
        t.put("remainingKm", round1(Math.max(0, total - done) / 1000));
        t.put("progress", total > 0 ? Math.round(done / total * 1000) / 1000.0 : 0);
        Double crossTrack = s != null ? Double.valueOf(s.crossTrack) : p.crossTrack();
        t.put("crossTrackMeters", crossTrack == null ? null : Math.round(crossTrack));
        t.put("pace", Math.round(pace * 100) / 100.0);
        t.put("departedAt", text(s != null ? s.departedAt : p.departedAt()));
        t.put("etaAt", text(eta));
        t.put("promisedEtaAt", text(promised));
        t.put("arrivedAt", text(p.arrivedAt() != null ? p.arrivedAt() : s == null ? null : s.arrivedAt));
        Instant end = p.arrivedAt() != null ? p.arrivedAt() : eta;
        t.put("lateMinutes", promised != null && end != null && !"WAITING".equals(status) ? Duration.between(promised, end).toMinutes() : null);
        t.put("deviationOpen", s != null && s.deviationOpen);
        t.put("deviationAddedMinutes", s != null && s.deviationOpen ? s.addedSeconds / 60 : null);
        t.put("wrongWay", s != null && s.wrongOpen);
        t.put("deviations", s != null ? s.deviations : p.deviations());
        t.put("deviationKm", round1(s != null ? s.deviationMeters / 1000 : p.deviationKm()));
        t.put("deviationMinutes", s != null ? s.deviationSeconds / 60 : p.deviationMinutes());
        t.put("wrongWays", s != null ? s.wrongWays : p.wrongWays());
        t.put("alternates", s != null ? s.alternates : p.alternates());
        t.put("lastReadingAt", text(lastAt));
        t.put("lastLat", s != null ? Double.valueOf(s.lastLatitude) : p.lastLat());
        t.put("lastLon", s != null ? Double.valueOf(s.lastLongitude) : p.lastLon());
        long silent = lastAt == null ? -1 : Duration.between(lastAt, now).toMinutes();
        t.put("silentMinutes", silent);
        t.put("noSignal", "ACTIVE".equals(p.status()) && lastAt != null && silent >= SILENT_AFTER_MINUTES);
        t.put("stops", stops(stopRows, plan, p, s, chainage, pace));
        return t;
    }

    private List<Map<String, Object>> stops(List<RoutePlanStore.Stop> rows, RoutePlan plan, RoutePlanStore.Plan p, RouteTrackState s,
                                            double chainage, double pace) {
        List<Map<String, Object>> out = new ArrayList<>();
        double total = p.offsetMeters() + p.lengthMeters();
        double staysBefore = 0;
        double staysAheadSoFar = 0;
        Instant start = p.plannedStartAt();
        // in the order the road meets them (stops off the current line last), so planned times add the stays before them
        List<RoutePlanStore.Stop> ordered = new ArrayList<>(rows);
        ordered.sort(java.util.Comparator.comparingDouble((RoutePlanStore.Stop r) -> r.chainage() < 0 ? Double.MAX_VALUE : r.chainage())
                .thenComparingInt(RoutePlanStore.Stop::seq));
        for (RoutePlanStore.Stop r : ordered) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.seq());
            m.put("name", r.name());
            m.put("kind", r.kind());
            m.put("placeId", r.placeId());
            m.put("latitude", r.latitude());
            m.put("longitude", r.longitude());
            m.put("status", r.status());
            m.put("stayMinutes", r.stayMinutes());
            m.put("arrivedAt", text(r.arrivedAt()));
            m.put("departedAt", text(r.departedAt()));
            m.put("missedAt", text(r.missedAt()));
            m.put("minutesInside", r.minutesInside());
            m.put("outOfOrder", r.outOfOrder());
            boolean onLine = r.chainage() >= 0;
            m.put("progress", onLine && total > 0 ? Math.round((p.offsetMeters() + r.chainage()) / total * 1000) / 1000.0 : null);
            m.put("km", onLine ? round1((p.offsetMeters() + r.chainage()) / 1000) : null);
            if (plan != null && onLine) {
                // planned: the trip's start + planned driving x the starting pace + stays before it
                m.put("plannedAt", text(start.plusSeconds(Math.round(plan.secondsAt(r.chainage()) * p.initialPace() + staysBefore))));
                if (s != null && s.lastAt != null && "PENDING".equals(r.status()) && r.chainage() > chainage) {
                    double driving = (plan.secondsAt(r.chainage()) - plan.secondsAt(chainage)) * pace;
                    m.put("etaAt", text(s.lastAt.plusSeconds(Math.round(driving + staysAheadSoFar))));
                }
            }
            staysBefore += r.stayMinutes() * 60.0;
            if ("PENDING".equals(r.status()) && r.chainage() > chainage) {
                staysAheadSoFar += r.stayMinutes() * 60.0;
            }
            out.add(m);
        }
        return out;
    }

    private RouteTrackState state(RoutePlanStore.Plan p) {
        if (p.imei() == null) {
            return null;
        }
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(ModuleRunner.stateKey("route-deviation", p.imei()));
            Map<String, String> fields = new LinkedHashMap<>();
            raw.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
            RouteTrackState s = RouteTrackState.fromFields(fields);
            return s != null && s.planId == p.id() ? s : null;
        } catch (RuntimeException redisDown) {
            return null;
        }
    }

    private RoutePlan plan(RoutePlanStore.Plan p, List<RoutePlanStore.Stop> stops) {
        return directory.build(p, stops);
    }

    /** The truck's readings for the trip, thinned for drawing, each marked off the line or not. */
    private List<Object[]> trail(RoutePlanStore.Plan p, RoutePlan plan, Instant from, Instant to) {
        if (p.imei() == null && p.vehicleNo() == null) {
            return List.of();
        }
        MapSqlParameterSource args = new MapSqlParameterSource("imei", p.imei()).addValue("vehicle", p.vehicleNo())
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        String who = p.imei() != null ? "imei = :imei" : "vehicle_registration_no = :vehicle";
        Integer count = jdbc.queryForObject("SELECT count(*) FROM gps_position WHERE " + who
                + " AND recorded_at BETWEEN :from AND :to", args, Integer.class);
        int step = Math.max(1, (count == null ? 0 : count) / MAP_POINTS);
        args.addValue("step", step);
        List<Object[]> points = new ArrayList<>();
        double[] last = {Double.NaN};
        jdbc.query("""
                SELECT recorded_at, latitude, longitude, speed_kmph FROM (
                    SELECT recorded_at, latitude, longitude, speed_kmph, row_number() OVER (ORDER BY recorded_at) AS n
                    FROM gps_position WHERE %s AND recorded_at BETWEEN :from AND :to) x
                WHERE (n - 1) %% :step = 0 ORDER BY recorded_at
                """.formatted(who), args, rs -> {
            double lat = rs.getDouble("latitude");
            double lon = rs.getDouble("longitude");
            RoutePlan.Projection pr = plan.project(lat, lon, last[0]);
            boolean off = pr.crossTrackMeters() > pr.corridorMeters();
            if (!off) {
                last[0] = pr.chainageMeters();
            }
            points.add(new Object[] {lat, lon, rs.getTimestamp("recorded_at").toInstant().toString(), rs.getInt("speed_kmph"), off});
        });
        return points;
    }

    private List<Map<String, Object>> events(RoutePlanStore.Plan p, Instant from) {
        if (p.imei() == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                SELECT event_type, severity, status, started_at, ended_at, latitude, longitude, details::text AS details
                FROM vehicle_event
                WHERE imei = :imei AND started_at >= :from AND event_type IN (:types) AND details->>'planId' = :plan
                ORDER BY started_at
                """, new MapSqlParameterSource("imei", p.imei()).addValue("from", Timestamp.from(from)).addValue("types", ROUTE_EVENTS)
                .addValue("plan", String.valueOf(p.id())), rs -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", rs.getString("event_type"));
            e.put("severity", rs.getString("severity"));
            e.put("status", rs.getString("status"));
            e.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
            Timestamp ended = rs.getTimestamp("ended_at");
            e.put("endedAt", ended == null ? null : ended.toInstant().toString());
            e.put("latitude", rs.getDouble("latitude"));
            e.put("longitude", rs.getDouble("longitude"));
            e.put("details", GpsMessageJson.fromJson(rs.getString("details"), LinkedHashMap.class));
            out.add(e);
        });
        return out;
    }

    /** Route events in a window (Alerts -> Route deviation -> Deviation log), newest first. */
    public List<Map<String, Object>> log(Instant from, Collection<String> vehicles, int limit) {
        MapSqlParameterSource args = new MapSqlParameterSource("from", Timestamp.from(from)).addValue("limit", limit)
                .addValue("types", List.of("ROUTE_DEVIATION", "ROUTE_ALTERNATE", "WRONG_DIRECTION", "DESTINATION_REACHED", "WAYPOINT_MISSED"));
        String scope = "";
        if (vehicles != null) {
            if (vehicles.isEmpty()) {
                return List.of();
            }
            scope = " AND vehicle_registration_no IN (:vehicles)";
            args.addValue("vehicles", vehicles);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                SELECT event_type, severity, status, started_at, ended_at, vehicle_registration_no, imei, latitude, longitude,
                       details::text AS details
                FROM vehicle_event WHERE event_type IN (:types) AND started_at >= :from
                """ + scope + " ORDER BY started_at DESC LIMIT :limit", args, rs -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", rs.getString("event_type"));
            e.put("severity", rs.getString("severity"));
            e.put("status", rs.getString("status"));
            e.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
            Timestamp ended = rs.getTimestamp("ended_at");
            e.put("endedAt", ended == null ? null : ended.toInstant().toString());
            e.put("vehicle", rs.getString("vehicle_registration_no"));
            e.put("imei", rs.getString("imei"));
            e.put("latitude", rs.getDouble("latitude"));
            e.put("longitude", rs.getDouble("longitude"));
            e.put("details", GpsMessageJson.fromJson(rs.getString("details"), LinkedHashMap.class));
            out.add(e);
        });
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String text(Instant t) {
        return t == null ? null : t.toString();
    }
}
