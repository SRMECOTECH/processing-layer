package com.srm.gps.application.road;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Writes the Road Intelligence records of V14: the points on the road (road_point), the road of each vehicle event,
 * the history of limit changes (road_limit_change) and the log of map-matching runs (road_match_run). A failure here is
 * logged and never stops the matching or a limit change.
 */
@Service
public class RoadRecords {

    private static final Logger LOG = LoggerFactory.getLogger(RoadRecords.class);
    /** How far back an event's start may be from the readings just matched (a long stoppage is raised late). */
    private static final Duration EVENT_LOOKBACK = Duration.ofHours(6);
    /** How far before an event's start the reading that gives its road may be. */
    private static final Duration READING_BEFORE_EVENT = Duration.ofMinutes(10);

    /** One map-matching run, for the log. */
    public record Run(String kind, String imei, Long tripNumber, Instant readingsFrom, Instant readingsUntil, Instant startedAt,
                      RoadMatcher.Result result, int piecesAdded, String outcome, String message) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RoadMatcher matcher;

    public RoadRecords(NamedParameterJdbcTemplate jdbc, RoadMatcher matcher) {
        this.jdbc = jdbc;
        this.matcher = matcher;
    }

    /** Stores the points (new ones inserted, known ones counted again and refreshed). */
    public void storePoints(Collection<RoadPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>(points.size());
        for (RoadPoint p : points) {
            batch.add(new MapSqlParameterSource("key", p.key()).addValue("lat", p.latitude()).addValue("lon", p.longitude())
                    .addValue("type", p.type()).addValue("signal", p.trafficSignal()).addValue("fork", p.fork())
                    .addValue("side", p.sideRoads()).addValue("classes", p.sideRoadClasses())
                    .addValue("intersecting", p.intersecting()).addValue("segment", p.segmentKey()));
        }
        try {
            jdbc.batchUpdate("""
                    INSERT INTO road_point (point_key, latitude, longitude, node_type, traffic_signal, fork, side_roads,
                                            side_road_classes, intersecting, segment_key)
                    VALUES (:key, :lat, :lon, :type, :signal, :fork, :side, :classes, CAST(:intersecting AS jsonb), :segment)
                    ON CONFLICT (point_key) DO UPDATE SET node_type = EXCLUDED.node_type, traffic_signal = EXCLUDED.traffic_signal,
                        fork = EXCLUDED.fork, side_roads = greatest(road_point.side_roads, EXCLUDED.side_roads),
                        side_road_classes = coalesce(EXCLUDED.side_road_classes, road_point.side_road_classes),
                        intersecting = coalesce(EXCLUDED.intersecting, road_point.intersecting),
                        times_seen = road_point.times_seen + 1, updated_at = now()
                    """, batch.toArray(MapSqlParameterSource[]::new));
        } catch (RuntimeException problem) {
            LOG.warn("Road points not stored: {}", problem.getMessage());
        }
    }

    /**
     * Gives the device's events that have no road yet the road saved on its reading at (or up to 10 minutes before) the
     * event's start: events that started up to 6 hours before {@code from} and until {@code until}. Returns how many.
     */
    public int linkEvents(String imei, Instant from, Instant until) {
        try {
            return jdbc.update("""
                    UPDATE vehicle_event e SET road_segment_id = r.road_segment_id,
                        road_label = coalesce(s.road_ref || ' ' || s.road_name, s.road_ref, s.road_name, 'unnamed ' || s.road_class),
                        road_class = s.road_class
                    FROM (SELECT ev.id,
                                 (SELECT p.road_segment_id FROM gps_position p
                                  WHERE p.imei = ev.imei AND p.recorded_at <= ev.started_at
                                    AND p.recorded_at > ev.started_at - CAST(:before AS interval)
                                  ORDER BY p.recorded_at DESC LIMIT 1) AS road_segment_id
                          FROM vehicle_event ev
                          WHERE ev.imei = :imei AND ev.road_segment_id IS NULL
                            AND ev.started_at > :from AND ev.started_at <= :until) r
                    JOIN road_segment s ON s.id = r.road_segment_id
                    WHERE e.id = r.id
                    """, new MapSqlParameterSource("imei", imei).addValue("from", Timestamp.from(from.minus(EVENT_LOOKBACK)))
                    .addValue("until", Timestamp.from(until)).addValue("before", READING_BEFORE_EVENT.toMinutes() + " minutes"));
        } catch (RuntimeException problem) {
            LOG.warn("Events of {} not linked to roads: {}", imei, problem.getMessage());
            return 0;
        }
    }

    /** One row in the history of speed limits. */
    public void limitChanged(String by, String scope, String target, Integer pieces, Integer oldLimit, Integer newLimit,
                             Integer oldMultiLane, Integer newMultiLane, String note) {
        try {
            jdbc.update("""
                    INSERT INTO road_limit_change (changed_by, scope, target, pieces, old_limit_kmph, new_limit_kmph,
                                                   old_multi_lane_limit_kmph, new_multi_lane_limit_kmph, note)
                    VALUES (:by, :scope, :target, :pieces, :old, :new, :oldMulti, :newMulti, :note)
                    """, new MapSqlParameterSource("by", cut(by == null || by.isBlank() ? "api" : by, 100))
                    .addValue("scope", scope).addValue("target", cut(target, 300)).addValue("pieces", pieces)
                    .addValue("old", oldLimit).addValue("new", newLimit).addValue("oldMulti", oldMultiLane)
                    .addValue("newMulti", newMultiLane).addValue("note", cut(note, 255)));
        } catch (RuntimeException problem) {
            LOG.warn("Limit change not recorded: {}", problem.getMessage());
        }
    }

    /** One row in the log of map-matching runs, with Valhalla's version and map date. */
    public void runFinished(Run run) {
        RoadMatcher.Result r = run.result();
        RoadMatcher.ValhallaInfo info = matcher.info();
        try {
            jdbc.update("""
                    INSERT INTO road_match_run (started_at, finished_at, kind, imei, trip_number, readings_from, readings_until,
                        points_sent, matched_points, unmatched_points, calls, failed_pieces, pieces_matched, pieces_added,
                        points_found, valhalla_ms, outcome, message, valhalla_url, valhalla_version, tiles_built_at)
                    VALUES (:started, now(), :kind, :imei, :trip, :from, :until, :sent, :matched, :unmatched, :calls, :failed,
                        :pieces, :added, :points, :ms, :outcome, :message, :url, :version, :tiles)
                    """, new MapSqlParameterSource("started", Timestamp.from(run.startedAt())).addValue("kind", run.kind())
                    .addValue("imei", run.imei()).addValue("trip", run.tripNumber())
                    .addValue("from", run.readingsFrom() == null ? null : Timestamp.from(run.readingsFrom()))
                    .addValue("until", run.readingsUntil() == null ? null : Timestamp.from(run.readingsUntil()))
                    .addValue("sent", r == null ? 0 : r.pointsSent()).addValue("matched", r == null ? 0 : r.matchedPoints())
                    .addValue("unmatched", r == null ? 0 : r.unmatchedPoints()).addValue("calls", r == null ? 0 : r.pieces())
                    .addValue("failed", r == null ? 0 : r.failedPieces()).addValue("pieces", r == null ? 0 : r.roads().size())
                    .addValue("added", run.piecesAdded()).addValue("points", r == null ? 0 : r.points().size())
                    .addValue("ms", r == null ? 0 : r.millis()).addValue("outcome", run.outcome())
                    .addValue("message", cut(run.message(), 500)).addValue("url", cut(matcher.valhallaUrl(), 200))
                    .addValue("version", info == null ? null : cut(info.version(), 40))
                    .addValue("tiles", info == null || info.tilesBuilt() == null ? null : Timestamp.from(info.tilesBuilt())));
        } catch (RuntimeException problem) {
            LOG.warn("Matching run not logged: {}", problem.getMessage());
        }
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
