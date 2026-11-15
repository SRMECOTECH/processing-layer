package com.srm.gps.application.road;

import com.srm.gps.application.report.ReportProperties;
import com.srm.gps.application.streaming.SpeedPolicyStore;
import com.srm.gps.application.streaming.SpeedRuleStore;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.road.RoadDayTotals;
import com.srm.gps.processing.speed.SpeedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The day-end road and speed report: for every vehicle and day, the roads it drove (vehicle_day_road, rebuilt from
 * gps_position for today and yesterday every few minutes) and the over-speed episodes the speed module raised that day,
 * each with the road and the limit it was judged by.
 */
@Service
public class RoadDayReportService {

    private static final Logger LOG = LoggerFactory.getLogger(RoadDayReportService.class);

    private static final String POSITIONS_OF_THE_DAY = """
            SELECT imei, tenant_code, vehicle_registration_no, recorded_at, latitude, longitude, speed_kmph,
                   distance_from_previous_m, seconds_since_previous
            FROM gps_position WHERE recorded_at >= :from AND recorded_at < :to
            ORDER BY imei, recorded_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RoadLibrary library;
    private final RoadProperties properties;
    private final SpeedRuleStore rules;
    private final SpeedPolicyStore policies;
    private final ZoneId zone;
    private volatile boolean backfilled;

    public RoadDayReportService(NamedParameterJdbcTemplate jdbc, RoadLibrary library, RoadProperties properties,
                                SpeedRuleStore rules, SpeedPolicyStore policies, ReportProperties reportProperties) {
        this.jdbc = jdbc;
        this.library = library;
        this.properties = properties;
        this.rules = rules;
        this.policies = policies;
        this.zone = reportProperties.zoneOrDefault();
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    @Scheduled(fixedDelayString = "${gps.reports.refresh-every:5m}", initialDelayString = "${gps.roads.first-report-after:90s}")
    public void refreshRecentDays() {
        try {
            LocalDate today = today();
            int days = backfilled ? 2 : 10;
            for (int i = 0; i < days; i++) {
                summarize(today.minusDays(i));
            }
            backfilled = true;
        } catch (RuntimeException problem) {
            LOG.warn("Could not refresh the road report: {}", problem.getMessage());
        }
    }

    private record Device(String imei, String tenant, String registration, List<RoadDayTotals.Reading> readings) {
    }

    /** Rebuilds the road rows of one day from its positions; a day without positions keeps its rows. Returns vehicles. */
    public synchronized int summarize(LocalDate day) {
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        Map<String, Device> devices = new LinkedHashMap<>();
        jdbc.query(POSITIONS_OF_THE_DAY, new MapSqlParameterSource("from", Timestamp.from(from)).addValue("to", Timestamp.from(to)), rs -> {
            String imei = rs.getString("imei");
            Device device = devices.get(imei);
            if (device == null) {
                device = new Device(imei, rs.getString("tenant_code"), rs.getString("vehicle_registration_no"), new ArrayList<>());
                devices.put(imei, device);
            }
            device.readings().add(new RoadDayTotals.Reading(rs.getTimestamp("recorded_at").toInstant(), rs.getDouble("latitude"),
                    rs.getDouble("longitude"), rs.getInt("speed_kmph"), rs.getDouble("distance_from_previous_m"),
                    rs.getLong("seconds_since_previous")));
        });
        if (devices.isEmpty()) {
            return 0;
        }
        RoadLibrary.Snapshot snapshot = library.snapshot();
        for (Device device : devices.values()) {
            int companyLimit = (int) Math.round(rules.ruleFor(device.registration(), device.tenant()).limitKmph());
            List<RoadDayTotals.Row> rows = RoadDayTotals.of(device.readings(), snapshot.index(), snapshot.table(), companyLimit,
                    properties.maxGapSecondsOrDefault(), properties.matchMetersOrDefault());
            store(day, device, rows);
        }
        return devices.size();
    }

    private void store(LocalDate day, Device device, List<RoadDayTotals.Row> rows) {
        MapSqlParameterSource key = new MapSqlParameterSource("day", day).addValue("imei", device.imei());
        jdbc.update("DELETE FROM vehicle_day_road WHERE day = :day AND imei = :imei", key);
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (RoadDayTotals.Row r : rows) {
            batch.add(new MapSqlParameterSource("day", day).addValue("imei", device.imei())
                    .addValue("key", cut(r.roadKey(), 400)).addValue("tenant", device.tenant())
                    .addValue("registration", device.registration()).addValue("label", cut(r.label(), 300))
                    .addValue("ref", r.ref()).addValue("class", r.roadClass()).addValue("area", r.area())
                    .addValue("state", r.stateCode()).addValue("limit", r.limitKmph()).addValue("source", r.limitSource())
                    .addValue("readings", r.readings()).addValue("distance", r.distanceM()).addValue("moving", r.movingS())
                    .addValue("max", r.maxSpeedKmph()).addValue("avg", r.averageMovingSpeedKmph())
                    .addValue("overReadings", r.overReadings()).addValue("overDistance", r.overDistanceM())
                    .addValue("overS", r.overS()).addValue("excess", r.maxExcessKmph()));
        }
        jdbc.batchUpdate("""
                INSERT INTO vehicle_day_road (day, imei, road_key, tenant_code, vehicle_registration_no, road_label, road_ref,
                    road_class, area, state_code, limit_kmph, limit_source, readings, distance_m, moving_s, max_speed_kmph,
                    avg_moving_speed_kmph, over_readings, over_distance_m, over_s, max_excess_kmph)
                VALUES (:day, :imei, :key, :tenant, :registration, :label, :ref, :class, :area, :state, :limit, :source,
                    :readings, :distance, :moving, :max, :avg, :overReadings, :overDistance, :overS, :excess)
                """, batch.toArray(MapSqlParameterSource[]::new));
    }

    /** The days that have road rows, newest first, with their vehicles. */
    public List<Map<String, Object>> days(int limit) {
        return jdbc.queryForList("""
                SELECT day, count(DISTINCT imei) AS vehicles, round(sum(distance_m)::numeric / 1000, 1) AS km
                FROM vehicle_day_road GROUP BY day ORDER BY day DESC LIMIT :limit
                """, new MapSqlParameterSource("limit", limit));
    }

    /**
     * The report of one day (one vehicle, or every vehicle when imei is null): totals per vehicle, roads, road classes,
     * every over-speed episode with its road, the roads with most episodes, night driving, and data quality.
     */
    public Map<String, Object> report(LocalDate day, String imei) {
        MapSqlParameterSource params = new MapSqlParameterSource("day", day).addValue("imei", imei);
        String vehicleFilter = imei == null || imei.isBlank() ? "" : " AND imei = :imei";
        List<Map<String, Object>> roads = jdbc.queryForList("""
                SELECT imei, vehicle_registration_no AS registration, road_label AS label, road_ref AS ref, road_class AS "roadClass",
                       area, state_code AS "stateCode", limit_kmph AS "limitKmph", limit_source AS "limitSource", readings,
                       round(distance_m::numeric / 1000, 2) AS km, round(moving_s / 60.0, 1) AS "movingMin",
                       max_speed_kmph AS "maxSpeedKmph", round(avg_moving_speed_kmph::numeric, 1) AS "avgMovingKmph",
                       over_readings AS "overReadings", round(over_distance_m::numeric / 1000, 2) AS "overKm",
                       round(over_s / 60.0, 1) AS "overMin", max_excess_kmph AS "maxExcessKmph", computed_at AS "computedAt"
                FROM vehicle_day_road WHERE day = :day""" + vehicleFilter + " ORDER BY distance_m DESC", params);

        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        params.addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        List<Map<String, Object>> events = new ArrayList<>();
        SpeedPolicy policy = policies.current();
        int night = 0;
        jdbc.query("""
                SELECT imei, vehicle_registration_no, severity, status, started_at, ended_at, latitude, longitude, details::text AS details
                FROM vehicle_event WHERE event_type = 'OVERSPEED' AND started_at >= :from AND started_at < :to""" + vehicleFilter
                + " ORDER BY started_at", params, rs -> {
            Map<String, Object> d = parse(rs.getString("details"));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("imei", rs.getString("imei"));
            e.put("registration", rs.getString("vehicle_registration_no"));
            e.put("severity", rs.getString("severity"));
            e.put("status", rs.getString("status"));
            e.put("startedAt", rs.getTimestamp("started_at").toInstant());
            Timestamp ended = rs.getTimestamp("ended_at");
            e.put("endedAt", ended == null ? null : ended.toInstant());
            e.put("latitude", d.getOrDefault("peakLatitude", rs.getDouble("latitude")));
            e.put("longitude", d.getOrDefault("peakLongitude", rs.getDouble("longitude")));
            for (String field : List.of("band", "limitKmph", "excessKmph", "maxSpeedKmph", "averageSpeedKmph", "durationSeconds",
                    "verdict", "roadLabel", "roadClass", "roadArea", "limitSource", "zoneName", "ruleScope", "distanceM")) {
                e.put(field, d.get(field));
            }
            events.add(e);
        });
        for (Map<String, Object> e : events) {
            ZonedDateTime local = ((Instant) e.get("startedAt")).atZone(zone);
            boolean atNight = policy.night(local.toLocalTime());
            e.put("night", atNight);
            if (atNight) {
                night++;
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("day", day);
        report.put("zone", zone.getId());
        report.put("vehicles", vehicles(roads, events));
        report.put("roads", roads);
        report.put("roadClasses", byClass(roads));
        report.put("events", events);
        report.put("hotspots", hotspots(events));
        report.put("nightEvents", night);
        report.put("nightHours", policy.nightStartHour() + ":00-" + policy.nightEndHour() + ":00");
        report.put("dataQuality", quality(roads));
        return report;
    }

    private static List<Map<String, Object>> vehicles(List<Map<String, Object>> roads, List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> byImei = new LinkedHashMap<>();
        for (Map<String, Object> r : roads) {
            Map<String, Object> v = byImei.computeIfAbsent((String) r.get("imei"), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("imei", k);
                m.put("registration", r.get("registration"));
                m.put("km", 0.0);
                m.put("movingMin", 0.0);
                m.put("overKm", 0.0);
                m.put("overMin", 0.0);
                m.put("maxSpeedKmph", 0);
                m.put("roads", 0);
                m.put("events", 0);
                return m;
            });
            add(v, "km", r.get("km"));
            add(v, "movingMin", r.get("movingMin"));
            add(v, "overKm", r.get("overKm"));
            add(v, "overMin", r.get("overMin"));
            v.put("maxSpeedKmph", Math.max((int) v.get("maxSpeedKmph"), ((Number) r.get("maxSpeedKmph")).intValue()));
            if (!RoadDayTotals.UNKNOWN_ROAD.equals(r.get("label"))) {
                v.put("roads", (int) v.get("roads") + 1);
            }
        }
        for (Map<String, Object> e : events) {
            Map<String, Object> v = byImei.get((String) e.get("imei"));
            if (v != null) {
                v.put("events", (int) v.get("events") + 1);
            }
        }
        for (Map<String, Object> v : byImei.values()) {
            double km = (double) v.get("km");
            v.put("km", round(km, 1));
            v.put("movingMin", round((double) v.get("movingMin"), 0));
            v.put("overKm", round((double) v.get("overKm"), 2));
            v.put("overMin", round((double) v.get("overMin"), 1));
            // per 100 km, so a long day and a short day compare
            v.put("overKmPer100Km", km < 1 ? null : round((double) v.get("overKm") / km * 100, 1));
            v.put("eventsPer100Km", km < 1 ? null : round((int) v.get("events") / km * 100, 2));
        }
        return new ArrayList<>(byImei.values());
    }

    private static List<Map<String, Object>> byClass(List<Map<String, Object>> roads) {
        Map<String, Map<String, Object>> classes = new LinkedHashMap<>();
        for (Map<String, Object> r : roads) {
            String roadClass = String.valueOf(r.get("roadClass"));
            Map<String, Object> c = classes.computeIfAbsent(roadClass.isEmpty() ? "unknown" : roadClass, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("roadClass", k);
                m.put("km", 0.0);
                m.put("overKm", 0.0);
                m.put("movingMin", 0.0);
                return m;
            });
            add(c, "km", r.get("km"));
            add(c, "overKm", r.get("overKm"));
            add(c, "movingMin", r.get("movingMin"));
        }
        List<Map<String, Object>> rows = new ArrayList<>(classes.values());
        for (Map<String, Object> c : rows) {
            c.put("km", round((double) c.get("km"), 1));
            c.put("overKm", round((double) c.get("overKm"), 2));
            c.put("movingMin", round((double) c.get("movingMin"), 0));
        }
        rows.sort(Comparator.comparingDouble((Map<String, Object> c) -> (double) c.get("km")).reversed());
        return rows;
    }

    private static List<Map<String, Object>> hotspots(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> byRoad = new LinkedHashMap<>();
        for (Map<String, Object> e : events) {
            String road = e.get("roadLabel") == null ? "(road not known)" : String.valueOf(e.get("roadLabel"));
            Map<String, Object> h = byRoad.computeIfAbsent(road, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("road", k);
                m.put("events", 0);
                m.put("vehicles", new java.util.TreeSet<String>());
                m.put("worstExcessKmph", 0.0);
                return m;
            });
            h.put("events", (int) h.get("events") + 1);
            @SuppressWarnings("unchecked")
            java.util.Set<String> vehicles = (java.util.Set<String>) h.get("vehicles");
            vehicles.add(String.valueOf(e.get("imei")));
            if (e.get("excessKmph") instanceof Number n) {
                h.put("worstExcessKmph", Math.max((double) h.get("worstExcessKmph"), n.doubleValue()));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(byRoad.values());
        for (Map<String, Object> h : rows) {
            h.put("vehicles", ((java.util.Set<?>) h.get("vehicles")).size());
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> h) -> (int) h.get("events")).reversed());
        return rows.size() > 15 ? rows.subList(0, 15) : rows;
    }

    private static Map<String, Object> quality(List<Map<String, Object>> roads) {
        long readings = 0;
        long onKnownRoad = 0;
        for (Map<String, Object> r : roads) {
            long n = ((Number) r.get("readings")).longValue();
            readings += n;
            if (!RoadDayTotals.UNKNOWN_ROAD.equals(r.get("label"))) {
                onKnownRoad += n;
            }
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("readings", readings);
        q.put("onKnownRoad", onKnownRoad);
        q.put("onKnownRoadPercent", readings == 0 ? null : round(100.0 * onKnownRoad / readings, 1));
        return q;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : GpsMessageJson.fromJson(json, LinkedHashMap.class);
        } catch (RuntimeException broken) {
            return Map.of();
        }
    }

    private static void add(Map<String, Object> m, String key, Object value) {
        m.put(key, (double) m.get(key) + (value instanceof Number n ? n.doubleValue() : 0));
    }

    private static double round(double value, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(value * f) / f;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
