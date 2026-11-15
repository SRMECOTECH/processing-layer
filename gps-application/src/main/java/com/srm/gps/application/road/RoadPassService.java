package com.srm.gps.application.road;

import com.srm.gps.application.streaming.SpeedRuleStore;
import com.srm.gps.processing.road.RoadPasses;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the live map matching leaves behind for analysis (V13), run on the road-learning thread right after a device's
 * readings were matched:
 *
 *   1. the road under each of those readings (gps_position.road_segment_id, road_off_m): the nearest learned piece within
 *      the match distance, the same lookup the live speed check makes, now kept so history does not change when the
 *      library does;
 *   2. the device's road passes (trip_road_pass) from the first pass still open up to the newest matched reading.
 */
@Service
public class RoadPassService {

    private final NamedParameterJdbcTemplate jdbc;
    private final RoadLibrary library;
    private final RoadProperties properties;
    private final SpeedRuleStore rules;

    public RoadPassService(NamedParameterJdbcTemplate jdbc, RoadLibrary library, RoadProperties properties, SpeedRuleStore rules) {
        this.jdbc = jdbc;
        this.library = library;
        this.properties = properties;
        this.rules = rules;
    }

    /** Saves the road under each reading of the device in (after, until]; returns how many readings are on a road. */
    public int assignRoads(String imei, Instant after, Instant until) {
        List<Object[]> readings = jdbc.query("""
                SELECT recorded_at, latitude, longitude FROM gps_position
                WHERE imei = :imei AND recorded_at > :after AND recorded_at <= :until ORDER BY recorded_at
                """, window(imei, after, until),
                (rs, n) -> new Object[]{rs.getTimestamp("recorded_at"), rs.getDouble("latitude"), rs.getDouble("longitude")});
        List<MapSqlParameterSource> batch = new ArrayList<>(readings.size());
        int onRoad = 0;
        for (Object[] r : readings) {
            RoadLibrary.RoadAt road = library.locate((double) r[1], (double) r[2]);
            if (road != null) {
                onRoad++;
            }
            batch.add(new MapSqlParameterSource("imei", imei).addValue("at", r[0])
                    .addValue("road", road == null ? null : road.road().id())
                    .addValue("off", road == null ? null : (float) road.distanceMeters()));
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("""
                    UPDATE gps_position SET road_segment_id = :road, road_off_m = :off WHERE imei = :imei AND recorded_at = :at
                    """, batch.toArray(MapSqlParameterSource[]::new));
        }
        return onRoad;
    }

    /**
     * Rebuilds the device's passes from the one still open at {@code from} (or from {@code from}) up to {@code until}, the
     * newest reading the map matching has covered. Returns how many passes were written.
     */
    public int rebuildPasses(String imei, Instant from, Instant until) {
        MapSqlParameterSource params = window(imei, from, until);
        Timestamp start = jdbc.queryForObject("""
                SELECT least(:after, coalesce(min(entered_at), :after)) FROM trip_road_pass WHERE imei = :imei AND left_at >= :after
                """, params, Timestamp.class);
        params.addValue("start", start);
        jdbc.update("DELETE FROM trip_road_pass WHERE imei = :imei AND left_at >= :start", params);

        String[] vehicle = new String[2];
        List<RoadPasses.Reading> readings = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        jdbc.query("""
                SELECT tenant_code, vehicle_registration_no, recorded_at, latitude, longitude, speed_kmph, distance_from_previous_m,
                       seconds_since_previous, road_segment_id
                FROM gps_position WHERE imei = :imei AND recorded_at >= :start AND recorded_at <= :until ORDER BY recorded_at
                """, params, rs -> {
            vehicle[0] = rs.getString("tenant_code");
            vehicle[1] = rs.getString("vehicle_registration_no");
            Long road = (Long) rs.getObject("road_segment_id");
            if (road != null) {
                ids.add(road);
            }
            readings.add(new RoadPasses.Reading(rs.getTimestamp("recorded_at").toInstant(), rs.getDouble("latitude"),
                    rs.getDouble("longitude"), rs.getInt("speed_kmph"), rs.getDouble("distance_from_previous_m"),
                    rs.getLong("seconds_since_previous"), road));
        });
        if (readings.isEmpty()) {
            return 0;
        }
        Map<Long, RoadPasses.Piece> pieces = library.passPieces(ids);
        int companyLimit = (int) Math.round(rules.ruleFor(vehicle[1], vehicle[0]).limitKmph());
        List<RoadPasses.Pass> passes = RoadPasses.of(readings, pieces, library.snapshot().table(), companyLimit,
                properties.maxGapSecondsOrDefault());

        List<MapSqlParameterSource> batch = new ArrayList<>(passes.size());
        for (RoadPasses.Pass p : passes) {
            batch.add(new MapSqlParameterSource("imei", imei).addValue("tenant", vehicle[0]).addValue("registration", vehicle[1])
                    .addValue("key", cut(p.roadKey(), 400)).addValue("label", cut(p.label(), 300)).addValue("ref", p.ref())
                    .addValue("class", p.roadClass()).addValue("area", p.area()).addValue("roadLimit", p.roadLimitKmph())
                    .addValue("limit", p.limitKmph()).addValue("source", p.limitSource())
                    .addValue("entered", Timestamp.from(p.enteredAt())).addValue("left", Timestamp.from(p.leftAt()))
                    .addValue("readings", p.readings()).addValue("pieces", p.pieces()).addValue("distance", p.distanceM())
                    .addValue("moving", p.movingS()).addValue("stopped", p.stoppedS()).addValue("avg", p.avgMovingSpeedKmph())
                    .addValue("max", p.maxSpeedKmph()).addValue("expected", p.expectedSpeedKmph())
                    .addValue("overReadings", p.overReadings()).addValue("overS", p.overS()).addValue("excess", p.maxExcessKmph())
                    .addValue("bridges", p.bridges()).addValue("curvature", p.maxCurvature())
                    .addValue("firstSegment", p.firstSegmentId()).addValue("lastSegment", p.lastSegmentId())
                    .addValue("entryLat", p.entryLatitude()).addValue("entryLon", p.entryLongitude())
                    .addValue("exitLat", p.exitLatitude()).addValue("exitLon", p.exitLongitude()));
        }
        jdbc.batchUpdate("""
                INSERT INTO trip_road_pass (imei, tenant_code, vehicle_registration_no, trip_id, road_key, road_label, road_ref,
                    road_class, area, road_limit_kmph, limit_kmph, limit_source, entered_at, left_at, readings, pieces, distance_m,
                    moving_s, stopped_s, avg_moving_speed_kmph, max_speed_kmph, expected_speed_kmph, over_readings, over_s,
                    max_excess_kmph, bridges, max_curvature, first_segment_id, last_segment_id, entry_lat, entry_lon, exit_lat,
                    exit_lon)
                VALUES (:imei, :tenant, :registration,
                    (SELECT t.id FROM gps_trip t WHERE t.imei = :imei AND t.start_recorded_at <= :entered
                        AND t.end_recorded_at >= :entered ORDER BY t.start_recorded_at DESC LIMIT 1),
                    :key, :label, :ref, :class, :area, :roadLimit, :limit, :source, :entered, :left, :readings, :pieces, :distance,
                    :moving, :stopped, :avg, :max, :expected, :overReadings, :overS, :excess, :bridges, :curvature, :firstSegment,
                    :lastSegment, :entryLat, :entryLon, :exitLat, :exitLon)
                """, batch.toArray(MapSqlParameterSource[]::new));
        return passes.size();
    }

    private static MapSqlParameterSource window(String imei, Instant after, Instant until) {
        return new MapSqlParameterSource("imei", imei).addValue("after", Timestamp.from(after)).addValue("until", Timestamp.from(until));
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
