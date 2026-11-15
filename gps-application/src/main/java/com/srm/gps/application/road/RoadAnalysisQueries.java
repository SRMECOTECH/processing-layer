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

    /** The points on the road (V14): counts by type, the toll booths, gates and borders, the busiest junctions. */
    public Map<String, Object> points() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("totals", jdbc.queryForMap("""
                SELECT count(*) AS points,
                       count(*) FILTER (WHERE node_type = 'street_intersection' AND side_roads > 0) AS junctions,
                       count(*) FILTER (WHERE side_roads >= 2) AS "bigJunctions",
                       count(*) FILTER (WHERE node_type = 'toll_booth') AS "tollBooths",
                       count(*) FILTER (WHERE node_type = 'gate') AS gates,
                       count(*) FILTER (WHERE node_type = 'border_control') AS borders,
                       count(*) FILTER (WHERE traffic_signal) AS signals,
                       count(*) FILTER (WHERE fork) AS forks
                FROM road_point
                """, none));
        answer.put("types", jdbc.queryForList("""
                SELECT node_type AS type, count(*) AS points FROM road_point GROUP BY 1 ORDER BY 2 DESC
                """, none));
        answer.put("special", jdbc.queryForList("""
                SELECT p.id, p.node_type AS type, round(p.latitude::numeric, 5) AS lat, round(p.longitude::numeric, 5) AS lon,
                       p.times_seen AS "timesSeen", p.traffic_signal AS signal, p.side_road_classes AS "sideRoadClasses",
                       coalesce(s.road_ref || ' ' || s.road_name, s.road_ref, s.road_name, 'unnamed ' || s.road_class) AS road
                FROM road_point p LEFT JOIN road_segment s ON s.segment_key = p.segment_key
                WHERE p.node_type <> 'street_intersection' OR p.traffic_signal
                ORDER BY p.node_type, p.first_seen_at LIMIT 100
                """, none));
        answer.put("junctions", jdbc.queryForList("""
                SELECT p.id, round(p.latitude::numeric, 5) AS lat, round(p.longitude::numeric, 5) AS lon, p.side_roads AS "sideRoads",
                       p.side_road_classes AS "sideRoadClasses", p.times_seen AS "timesSeen", p.fork,
                       coalesce(s.road_ref || ' ' || s.road_name, s.road_ref, s.road_name, 'unnamed ' || s.road_class) AS road
                FROM road_point p LEFT JOIN road_segment s ON s.segment_key = p.segment_key
                WHERE p.side_roads >= 2 ORDER BY p.side_roads DESC, p.times_seen DESC LIMIT 50
                """, none));
        return answer;
    }

    /** The points to draw on a map: toll booths, gates, borders, signals and junctions with side roads. */
    public List<Map<String, Object>> mapPoints() {
        return jdbc.queryForList("""
                SELECT id, node_type AS type, round(latitude::numeric, 5) AS lat, round(longitude::numeric, 5) AS lon,
                       side_roads AS "sideRoads", traffic_signal AS signal
                FROM road_point WHERE node_type <> 'street_intersection' OR traffic_signal OR side_roads > 0
                """, new MapSqlParameterSource());
    }

    /** Vehicle events by road (V14): the roads with most events, and counts per event type. */
    public Map<String, Object> eventsByRoad(String imei) {
        String filter = imei == null ? "" : " AND imei = :imei";
        MapSqlParameterSource params = new MapSqlParameterSource("imei", imei);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("coverage", jdbc.queryForMap("""
                SELECT count(*) AS events, count(road_segment_id) AS "withRoad" FROM vehicle_event WHERE true""" + filter, params));
        answer.put("roads", jdbc.queryForList("""
                SELECT road_label AS road, road_class AS "roadClass", count(*) AS events,
                       count(DISTINCT imei) AS vehicles,
                       count(*) FILTER (WHERE event_type = 'OVERSPEED') AS overspeed,
                       count(*) FILTER (WHERE event_type IN ('STOPPAGE', 'PARKED')) AS stops,
                       count(*) FILTER (WHERE event_type LIKE 'HARSH%') AS harsh,
                       count(*) FILTER (WHERE event_type LIKE 'ROUTE%' OR event_type = 'WRONG_DIRECTION') AS route,
                       count(*) FILTER (WHERE event_type LIKE 'IGNITION%') AS ignition,
                       count(*) FILTER (WHERE event_type LIKE 'GEOFENCE%') AS geofence,
                       count(*) FILTER (WHERE event_type NOT IN ('OVERSPEED', 'STOPPAGE', 'PARKED') AND event_type NOT LIKE 'HARSH%'
                           AND event_type NOT LIKE 'ROUTE%' AND event_type <> 'WRONG_DIRECTION' AND event_type NOT LIKE 'IGNITION%'
                           AND event_type NOT LIKE 'GEOFENCE%') AS other
                FROM vehicle_event WHERE road_label IS NOT NULL""" + filter + """
                 GROUP BY road_label, road_class ORDER BY events DESC LIMIT 50
                """, params));
        answer.put("types", jdbc.queryForList("""
                SELECT event_type AS type, count(*) AS events, count(road_segment_id) AS "withRoad"
                FROM vehicle_event WHERE true""" + filter + " GROUP BY 1 ORDER BY 2 DESC", params));
        return answer;
    }

    /** The newest map-matching runs, and how the last day went (V14). */
    public Map<String, Object> matchRuns(int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("day", jdbc.queryForMap("""
                SELECT count(*) AS runs, count(*) FILTER (WHERE outcome = 'OK') AS ok,
                       count(*) FILTER (WHERE outcome = 'PARTLY') AS partly, count(*) FILTER (WHERE outcome = 'DOWN') AS down,
                       count(*) FILTER (WHERE outcome = 'FAILED') AS failed, coalesce(sum(points_sent), 0) AS "pointsSent",
                       coalesce(sum(matched_points), 0) AS "matchedPoints", coalesce(sum(unmatched_points), 0) AS "unmatchedPoints",
                       coalesce(sum(pieces_added), 0) AS "piecesAdded",
                       round(avg(valhalla_ms) FILTER (WHERE outcome IN ('OK', 'PARTLY'))) AS "avgMs",
                       max(valhalla_ms) AS "maxMs", max(valhalla_version) AS version, max(tiles_built_at) AS "tilesBuiltAt"
                FROM road_match_run WHERE started_at > now() - interval '1 day'
                """, params));
        answer.put("runs", jdbc.queryForList("""
                SELECT id, started_at AS "startedAt", finished_at AS "finishedAt", kind, imei, trip_number AS "tripNumber",
                       readings_from AS "readingsFrom", readings_until AS "readingsUntil", points_sent AS "pointsSent",
                       matched_points AS "matchedPoints", unmatched_points AS "unmatchedPoints", calls,
                       failed_pieces AS "failedPieces", pieces_matched AS "piecesMatched", pieces_added AS "piecesAdded",
                       points_found AS "pointsFound", valhalla_ms AS "valhallaMs", outcome, message
                FROM road_match_run ORDER BY started_at DESC LIMIT :limit
                """, params));
        return answer;
    }

    /** The history of speed-limit changes, newest first (V14). */
    public List<Map<String, Object>> limitChanges(int limit) {
        return jdbc.queryForList("""
                SELECT id, changed_at AS "changedAt", changed_by AS "changedBy", scope, target, pieces,
                       old_limit_kmph AS "oldLimitKmph", new_limit_kmph AS "newLimitKmph",
                       old_multi_lane_limit_kmph AS "oldMultiLaneKmph", new_multi_lane_limit_kmph AS "newMultiLaneKmph", note
                FROM road_limit_change ORDER BY changed_at DESC LIMIT :limit
                """, new MapSqlParameterSource("limit", limit));
    }

    /** A piece's hand-set limit and label before a change. */
    public Map<String, Object> pieceLimit(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT manual_limit_kmph AS "limit",
                       coalesce(road_ref || ' ' || road_name, road_ref, road_name, 'unnamed ' || road_class) AS label
                FROM road_segment WHERE id = :id
                """, new MapSqlParameterSource("id", id));
        return rows.isEmpty() ? new HashMap<>() : rows.getFirst();
    }

    /** A road's hand-set limit before a change: the one value its pieces share, or null (none, or mixed). */
    public Integer roadLimit(String ref, String name) {
        List<Integer> limits = jdbc.queryForList("""
                SELECT DISTINCT manual_limit_kmph FROM road_segment
                WHERE coalesce(road_ref, '') = coalesce(:ref, '') AND coalesce(road_name, '') = coalesce(:name, '')
                """, new MapSqlParameterSource("ref", ref).addValue("name", name), Integer.class);
        return limits.size() == 1 ? limits.getFirst() : null;
    }

    /** A row of the limit table before a change: {limit, multi-lane limit}, nulls when it did not exist. */
    public Integer[] tableRow(String roadClass, String area, String stateCode) {
        List<Integer[]> rows = jdbc.query("""
                SELECT limit_kmph, multi_lane_limit_kmph FROM road_speed_limit
                WHERE road_class = :class AND area = :area AND state_code = :state
                """, new MapSqlParameterSource("class", roadClass).addValue("area", area).addValue("state", stateCode),
                (rs, n) -> new Integer[]{(Integer) rs.getObject("limit_kmph"), (Integer) rs.getObject("multi_lane_limit_kmph")});
        return rows.isEmpty() ? new Integer[]{null, null} : rows.getFirst();
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
