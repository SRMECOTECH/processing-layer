package com.srm.gps.application.road;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only views of the analysis data (V13) for the console: what Valhalla told us about the learned roads, the details
 * of one piece, and the road passes of a vehicle.
 */
@Service
public class RoadAnalysisQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public RoadAnalysisQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The library's road details in totals: one-way, bridges, curves, ramps, Valhalla's speed against our limit... */
    public Map<String, Object> attributes() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("totals", jdbc.queryForMap("""
                SELECT count(*) AS pieces, round(coalesce(sum(length_m), 0)::numeric / 1000, 1) AS km,
                       count(attributes) AS "withDetails",
                       round(coalesce(sum(length_m) FILTER (WHERE traversability IN ('forward', 'backward')), 0)::numeric / 1000, 1) AS "oneWayKm",
                       round(coalesce(sum(length_m) FILTER (WHERE traversability = 'both'), 0)::numeric / 1000, 1) AS "twoWayKm",
                       count(*) FILTER (WHERE bridge) AS bridges,
                       round(coalesce(sum(length_m) FILTER (WHERE bridge), 0)::numeric / 1000, 2) AS "bridgeKm",
                       count(*) FILTER (WHERE tunnel) AS tunnels,
                       count(*) FILTER (WHERE roundabout) AS roundabouts,
                       count(*) FILTER (WHERE toll) AS "tollPieces",
                       count(*) FILTER (WHERE truck_route) AS "truckRoutePieces",
                       count(*) FILTER (WHERE curvature >= 5) AS "sharpCurves",
                       count(*) FILTER (WHERE osm_limit_kmph IS NOT NULL) AS "osmLimitPieces",
                       count(*) FILTER (WHERE manual_limit_kmph IS NOT NULL) AS "manualLimitPieces"
                FROM road_segment
                """, none));
        answer.put("uses", jdbc.queryForList("""
                SELECT coalesce(road_use, 'unknown') AS use, count(*) AS pieces, round(sum(length_m)::numeric / 1000, 1) AS km
                FROM road_segment GROUP BY 1 ORDER BY km DESC
                """, none));
        answer.put("curvature", jdbc.queryForList("""
                SELECT curvature, count(*) AS pieces, round(sum(length_m)::numeric / 1000, 1) AS km
                FROM road_segment WHERE curvature IS NOT NULL GROUP BY 1 ORDER BY 1
                """, none));
        answer.put("speedByClass", jdbc.queryForList("""
                SELECT road_class AS "roadClass", count(*) AS pieces, round(sum(length_m)::numeric / 1000, 1) AS km,
                       round((sum(length_m) FILTER (WHERE valhalla_speed_kmph > 0)
                              / nullif(sum(length_m / valhalla_speed_kmph) FILTER (WHERE valhalla_speed_kmph > 0), 0))::numeric, 1)
                           AS "valhallaKmph",
                       round(avg(lane_count)::numeric, 1) AS lanes
                FROM road_segment GROUP BY 1 ORDER BY km DESC
                """, none));
        answer.put("surfaces", jdbc.queryForList("""
                SELECT coalesce(surface, 'unknown') AS surface, count(*) AS pieces FROM road_segment GROUP BY 1 ORDER BY 2 DESC
                """, none));
        answer.put("bridges", jdbc.queryForList("""
                SELECT id, coalesce(road_ref || ' ' || road_name, road_ref, road_name, 'unnamed ' || road_class) AS label,
                       road_class AS "roadClass", round(length_m::numeric) AS "lengthM",
                       round(((min_lat + max_lat) / 2)::numeric, 5) AS lat, round(((min_lon + max_lon) / 2)::numeric, 5) AS lon
                FROM road_segment WHERE bridge ORDER BY length_m DESC LIMIT 50
                """, none));
        answer.put("curves", jdbc.queryForList("""
                SELECT id, coalesce(road_ref || ' ' || road_name, road_ref, road_name, 'unnamed ' || road_class) AS label,
                       road_class AS "roadClass", curvature, round(length_m::numeric) AS "lengthM",
                       round(((min_lat + max_lat) / 2)::numeric, 5) AS lat, round(((min_lon + max_lon) / 2)::numeric, 5) AS lon
                FROM road_segment WHERE curvature >= 5 ORDER BY curvature DESC, length_m DESC LIMIT 50
                """, none));
        answer.put("readings", jdbc.queryForMap("""
                SELECT count(*) AS readings, count(road_segment_id) AS "withRoad",
                       round(avg(road_off_m)::numeric, 1) AS "avgOffM",
                       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY road_off_m))::numeric, 1) AS "p95OffM"
                FROM gps_position WHERE stored_at > now() - interval '2 days'
                """, none));
        return answer;
    }

    /** Everything stored about one piece, the whole Valhalla edge included. */
    public Map<String, Object> piece(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT road_use AS use, traversability, bridge, tunnel, roundabout, toll, curvature,
                       valhalla_speed_kmph AS "valhallaKmph", speed_type AS "speedType", truck_route AS "truckRoute", surface,
                       begin_heading AS "beginHeading", end_heading AS "endHeading", country_code AS "countryCode",
                       way_id AS "wayId", osm_limit_kmph AS "osmLimitKmph", round(length_m::numeric) AS "lengthM",
                       times_matched AS "timesMatched", first_seen_at AS "firstSeenAt", attributes::text AS attributes
                FROM road_segment WHERE id = :id
                """, new MapSqlParameterSource("id", id));
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /** id -> [bridge, one-way, curvature] for the console's road map. */
    public Map<Long, Object[]> mapFlags() {
        Map<Long, Object[]> flags = new HashMap<>();
        jdbc.query("SELECT id, bridge, traversability, curvature FROM road_segment WHERE attributes IS NOT NULL",
                new MapSqlParameterSource(), rs -> {
                    String t = rs.getString("traversability");
                    flags.put(rs.getLong("id"), new Object[]{Boolean.TRUE.equals(rs.getObject("bridge")),
                            t != null && !"both".equals(t), rs.getObject("curvature")});
                });
        return flags;
    }

    /** A vehicle's road passes, newest first (imei null: every vehicle). */
    public List<Map<String, Object>> passes(String imei, int limit) {
        String filter = imei == null || imei.isBlank() ? "" : " WHERE imei = :imei";
        return jdbc.queryForList("""
                SELECT id, imei, vehicle_registration_no AS registration, trip_id AS "tripId", road_label AS label,
                       road_ref AS ref, road_class AS "roadClass", area, road_limit_kmph AS "roadLimitKmph",
                       limit_kmph AS "limitKmph", limit_source AS "limitSource", entered_at AS "enteredAt", left_at AS "leftAt",
                       readings, pieces, round(distance_m::numeric / 1000, 2) AS km, round(moving_s / 60.0, 1) AS "movingMin",
                       round(stopped_s / 60.0, 1) AS "stoppedMin", round(avg_moving_speed_kmph::numeric, 1) AS "avgKmph",
                       max_speed_kmph AS "maxKmph", round(expected_speed_kmph::numeric, 1) AS "expectedKmph",
                       over_readings AS "overReadings", round(over_s / 60.0, 1) AS "overMin", max_excess_kmph AS "maxExcessKmph",
                       bridges, max_curvature AS "maxCurvature", entry_lat AS "entryLat", entry_lon AS "entryLon",
                       exit_lat AS "exitLat", exit_lon AS "exitLon"
                FROM trip_road_pass""" + filter + " ORDER BY entered_at DESC LIMIT :limit",
                new MapSqlParameterSource("imei", imei).addValue("limit", limit));
    }
}
