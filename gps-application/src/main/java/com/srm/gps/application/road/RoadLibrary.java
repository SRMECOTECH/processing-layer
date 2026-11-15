package com.srm.gps.application.road;

import com.srm.gps.processing.road.RoadIndex;
import com.srm.gps.processing.road.RoadPasses;
import com.srm.gps.processing.road.RoadSegment;
import com.srm.gps.processing.road.RoadSpeedTable;
import com.srm.gps.processing.route.Polyline6;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The road library (PostgreSQL road_segment) and the limit table (road_speed_limit), held in memory for the live
 * speed check: {@link #locate} finds the road under a reading in microseconds. Rebuilt when the tables change (a new
 * piece learned, a limit edited on the console), checked every 30 s and right after every learning batch.
 */
@Component
public class RoadLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(RoadLibrary.class);

    private static final String SELECT = """
            SELECT id, segment_key, way_id, road_name, road_ref, road_class, density, lane_count, state_code, osm_limit_kmph,
                   manual_limit_kmph, length_m, shape
            FROM road_segment
            """;
    private static final String FINGERPRINT = """
            SELECT (SELECT count(*) || '|' || coalesce(max(updated_at)::text, '') FROM road_segment) || '|' ||
                   (SELECT count(*) || '|' || coalesce(max(updated_at)::text, '') FROM road_speed_limit)
            """;
    private static final String UPSERT = """
            INSERT INTO road_segment (segment_key, way_id, road_name, road_ref, road_class, density, lane_count, state_code,
                                      osm_limit_kmph, length_m, shape, min_lat, max_lat, min_lon, max_lon, road_use,
                                      traversability, bridge, tunnel, roundabout, toll, curvature, valhalla_speed_kmph,
                                      speed_type, truck_route, surface, begin_heading, end_heading, country_code, attributes)
            VALUES (:key, :way, :name, :ref, :class, :density, :lanes, :state, :osm, :length, :shape, :minLat, :maxLat,
                    :minLon, :maxLon, :use, :traversability, :bridge, :tunnel, :roundabout, :toll, :curvature, :vspeed,
                    :speedType, :truckRoute, :surface, :beginHeading, :endHeading, :country, CAST(:attributes AS jsonb))
            ON CONFLICT (segment_key) DO UPDATE SET
                road_name = EXCLUDED.road_name, road_ref = EXCLUDED.road_ref, road_class = EXCLUDED.road_class,
                density = EXCLUDED.density, lane_count = EXCLUDED.lane_count, state_code = EXCLUDED.state_code,
                osm_limit_kmph = EXCLUDED.osm_limit_kmph,
                road_use = coalesce(EXCLUDED.road_use, road_segment.road_use),
                traversability = coalesce(EXCLUDED.traversability, road_segment.traversability),
                bridge = coalesce(EXCLUDED.bridge, road_segment.bridge), tunnel = coalesce(EXCLUDED.tunnel, road_segment.tunnel),
                roundabout = coalesce(EXCLUDED.roundabout, road_segment.roundabout), toll = coalesce(EXCLUDED.toll, road_segment.toll),
                curvature = coalesce(EXCLUDED.curvature, road_segment.curvature),
                valhalla_speed_kmph = coalesce(EXCLUDED.valhalla_speed_kmph, road_segment.valhalla_speed_kmph),
                speed_type = coalesce(EXCLUDED.speed_type, road_segment.speed_type),
                truck_route = coalesce(EXCLUDED.truck_route, road_segment.truck_route),
                surface = coalesce(EXCLUDED.surface, road_segment.surface),
                begin_heading = coalesce(EXCLUDED.begin_heading, road_segment.begin_heading),
                end_heading = coalesce(EXCLUDED.end_heading, road_segment.end_heading),
                country_code = coalesce(EXCLUDED.country_code, road_segment.country_code),
                attributes = coalesce(EXCLUDED.attributes, road_segment.attributes),
                times_matched = road_segment.times_matched + 1, updated_at = now()
            RETURNING (xmax = 0) AS inserted
            """;

    /** A loaded library: the index and the table, swapped in together. */
    public record Snapshot(RoadIndex index, RoadSpeedTable table, int roads, double kilometres) {
    }

    /** The road under a reading with its limit (null limit: the table has no row for its class). */
    public record RoadAt(RoadSegment road, double distanceMeters, RoadSpeedTable.Limit limit) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RoadProperties properties;
    private volatile Snapshot snapshot;
    private volatile String fingerprint;

    public RoadLibrary(NamedParameterJdbcTemplate jdbc, RoadProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** The current library, loaded on first use. Throws when PostgreSQL is down and nothing was loaded yet. */
    public Snapshot snapshot() {
        Snapshot current = snapshot;
        return current != null ? current : reload();
    }

    /** The road under the point and its limit, or null (no stored road within matchMeters, or the library is unavailable). */
    public RoadAt locate(double latitude, double longitude) {
        Snapshot current;
        try {
            current = snapshot();
        } catch (RuntimeException databaseDown) {
            LOG.debug("Road library unavailable: {}", databaseDown.getMessage());
            return null;
        }
        RoadIndex.Match match = current.index().locate(latitude, longitude, properties.matchMetersOrDefault());
        if (match == null) {
            return null;
        }
        return new RoadAt(match.road(), match.distanceMeters(), current.table().limitFor(match.road()));
    }

    public synchronized Snapshot reload() {
        String print = jdbc.queryForObject(FINGERPRINT, new MapSqlParameterSource(), String.class);
        List<RoadSegment> roads = new ArrayList<>();
        double[] metres = {0};
        jdbc.query(SELECT, rs -> {
            try {
                RoadSegment road = map(rs);
                if (road.points().size() >= 2) {
                    roads.add(road);
                    metres[0] += road.lengthMeters();
                }
            } catch (RuntimeException broken) {
                LOG.warn("Road piece {} left out of the index: {}", rs.getLong("id"), broken.getMessage());
            }
        });
        Snapshot built = new Snapshot(new RoadIndex(roads), loadTable(), roads.size(), metres[0] / 1000);
        snapshot = built;
        fingerprint = print;
        LOG.info("Road library loaded: {} road pieces, {} km", built.roads(), Math.round(built.kilometres()));
        return built;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reloadIfChanged() {
        try {
            String print = jdbc.queryForObject(FINGERPRINT, new MapSqlParameterSource(), String.class);
            if (snapshot == null || !print.equals(fingerprint)) {
                reload();
            }
        } catch (RuntimeException databaseDown) {
            LOG.debug("Road library fingerprint not checked: {}", databaseDown.getMessage());
        }
    }

    /** Stores the pieces without details (see {@link #store(List, Map)}). */
    public int store(List<RoadSegment> pieces) {
        return store(pieces, Map.of());
    }

    /**
     * Stores the pieces (new ones inserted, known ones refreshed; a hand-set limit is kept) with the extra details Valhalla
     * gave for each (by piece key; a detail it did not send keeps the stored value). Returns how many were new.
     */
    public int store(List<RoadSegment> pieces, Map<String, RoadDetails> details) {
        int added = 0;
        for (RoadSegment piece : pieces) {
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (double[] p : piece.points()) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]);
                maxLon = Math.max(maxLon, p[1]);
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("key", piece.key()).addValue("way", piece.wayId()).addValue("name", cut(piece.name(), 255))
                    .addValue("ref", cut(piece.ref(), 40)).addValue("class", cut(piece.roadClass(), 20))
                    .addValue("density", piece.density()).addValue("lanes", piece.laneCount())
                    .addValue("state", cut(piece.stateCode(), 10)).addValue("osm", piece.osmLimitKmph())
                    .addValue("length", piece.lengthMeters()).addValue("shape", Polyline6.encode(piece.points()))
                    .addValue("minLat", minLat).addValue("maxLat", maxLat).addValue("minLon", minLon).addValue("maxLon", maxLon);
            RoadDetails d = details.get(piece.key());
            params.addValue("use", d == null ? null : d.use()).addValue("traversability", d == null ? null : d.traversability())
                    .addValue("bridge", d == null ? null : d.bridge()).addValue("tunnel", d == null ? null : d.tunnel())
                    .addValue("roundabout", d == null ? null : d.roundabout()).addValue("toll", d == null ? null : d.toll())
                    .addValue("curvature", d == null ? null : d.curvature()).addValue("vspeed", d == null ? null : d.valhallaSpeed())
                    .addValue("speedType", d == null ? null : d.speedType()).addValue("truckRoute", d == null ? null : d.truckRoute())
                    .addValue("surface", d == null ? null : d.surface()).addValue("beginHeading", d == null ? null : d.beginHeading())
                    .addValue("endHeading", d == null ? null : d.endHeading()).addValue("country", d == null ? null : d.countryCode())
                    .addValue("attributes", d == null ? null : d.attributes());
            Boolean inserted = jdbc.queryForObject(UPSERT, params, Boolean.class);
            if (Boolean.TRUE.equals(inserted)) {
                added++;
            }
        }
        return added;
    }

    /** Sets (or with null clears) the hand-set limit of a piece of road. */
    public boolean setManualLimit(long id, Integer limitKmph, String note) {
        int changed = jdbc.update("""
                UPDATE road_segment SET manual_limit_kmph = :limit, manual_note = :note, updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("limit", limitKmph).addValue("note", cut(note, 255)));
        if (changed > 0) {
            reload();
        }
        return changed > 0;
    }

    /** Sets the hand-set limit on every stored piece of one road (by its label's number or name), e.g. a whole bypass. */
    public int setManualLimitForRoad(String ref, String name, Integer limitKmph, String note) {
        int changed = jdbc.update("""
                UPDATE road_segment SET manual_limit_kmph = :limit, manual_note = :note, updated_at = now()
                WHERE coalesce(road_ref, '') = coalesce(:ref, '') AND coalesce(road_name, '') = coalesce(:name, '')
                """, new MapSqlParameterSource("ref", ref).addValue("name", name).addValue("limit", limitKmph)
                .addValue("note", cut(note, 255)));
        if (changed > 0) {
            reload();
        }
        return changed;
    }

    public RoadSpeedTable loadTable() {
        List<RoadSpeedTable.Row> rows = jdbc.query("""
                SELECT road_class, area, state_code, limit_kmph, multi_lane_limit_kmph FROM road_speed_limit
                ORDER BY road_class, area, state_code
                """, (rs, n) -> new RoadSpeedTable.Row(rs.getString("road_class"), RoadSpeedTable.Area.valueOf(rs.getString("area")),
                rs.getString("state_code"), rs.getInt("limit_kmph"), (Integer) rs.getObject("multi_lane_limit_kmph")));
        return new RoadSpeedTable(rows.isEmpty() ? RoadSpeedTable.defaults().rows() : rows, properties.townDensityFromOrDefault());
    }

    /** Creates or changes one row of the limit table. */
    public void saveTableRow(RoadSpeedTable.Row row) {
        if (row.limitKmph() < 5 || row.limitKmph() > 150
                || (row.multiLaneLimitKmph() != null && (row.multiLaneLimitKmph() < 5 || row.multiLaneLimitKmph() > 150))) {
            throw new IllegalArgumentException("Limits are 5 to 150 km/h");
        }
        jdbc.update("""
                INSERT INTO road_speed_limit (road_class, area, state_code, limit_kmph, multi_lane_limit_kmph)
                VALUES (:class, :area, :state, :limit, :multi)
                ON CONFLICT (road_class, area, state_code) DO UPDATE SET limit_kmph = EXCLUDED.limit_kmph,
                    multi_lane_limit_kmph = EXCLUDED.multi_lane_limit_kmph, updated_at = now()
                """, new MapSqlParameterSource("class", row.roadClass().toLowerCase(java.util.Locale.ROOT))
                .addValue("area", row.area().name())
                .addValue("state", row.stateCode() == null || row.stateCode().isBlank() ? RoadSpeedTable.ALL_STATES
                        : row.stateCode().toUpperCase(java.util.Locale.ROOT))
                .addValue("limit", row.limitKmph()).addValue("multi", row.multiLaneLimitKmph()));
        reload();
    }

    /** Deletes a state's own row (the all-India rows stay: every class needs one). */
    public boolean deleteTableRow(String roadClass, String area, String stateCode) {
        if (stateCode == null || RoadSpeedTable.ALL_STATES.equals(stateCode)) {
            throw new IllegalArgumentException("The all-India rows ('*') cannot be deleted, only changed");
        }
        int deleted = jdbc.update("DELETE FROM road_speed_limit WHERE road_class = :class AND area = :area AND state_code = :state",
                new MapSqlParameterSource("class", roadClass).addValue("area", area).addValue("state", stateCode));
        if (deleted > 0) {
            reload();
        }
        return deleted > 0;
    }

    /**
     * The stored roads grouped by road (number + name + class + state), for the console: how much of each is known and
     * the limit that applies, with the source. Filtered by text in the number or name.
     */
    public List<Map<String, Object>> roads(String query, int limit) {
        Snapshot current = snapshot();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        for (RoadSegment road : allRoads()) {
            if (!q.isEmpty() && !road.label().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                continue;
            }
            RoadSpeedTable.Limit l = current.table().limitFor(road);
            String key = road.label() + "|" + road.roadClass() + "|" + road.stateCode() + "|" + (l == null ? "" : l.kmph() + l.source().name());
            Map<String, Object> row = grouped.computeIfAbsent(key, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("label", road.label());
                r.put("ref", road.ref());
                r.put("name", road.name());
                r.put("roadClass", road.roadClass());
                r.put("stateCode", road.stateCode());
                r.put("area", current.table().areaOf(road).name());
                r.put("limitKmph", l == null ? null : l.kmph());
                r.put("limitSource", l == null ? null : l.source().name());
                r.put("pieces", 0);
                r.put("km", 0.0);
                r.put("firstPieceId", road.id());
                return r;
            });
            row.put("pieces", (int) row.get("pieces") + 1);
            row.put("km", Math.round(((double) row.get("km") + road.lengthMeters() / 1000) * 10) / 10.0);
        }
        List<Map<String, Object>> rows = new ArrayList<>(grouped.values());
        rows.sort((a, b) -> Double.compare((double) b.get("km"), (double) a.get("km")));
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    /**
     * Every stored piece with its line, class and limit, for the console's road map (drawn in-house, no map tiles).
     * Coordinates are rounded to 5 decimals (about 1 m).
     */
    public List<Map<String, Object>> network() {
        Snapshot current = snapshot();
        List<Map<String, Object>> pieces = new ArrayList<>();
        for (RoadSegment road : allRoads()) {
            RoadSpeedTable.Limit l = current.table().limitFor(road);
            List<double[]> line = new ArrayList<>(road.points().size());
            for (double[] p : road.points()) {
                line.add(new double[]{Math.round(p[0] * 1e5) / 1e5, Math.round(p[1] * 1e5) / 1e5});
            }
            Map<String, Object> piece = new LinkedHashMap<>();
            piece.put("id", road.id());
            piece.put("label", road.label());
            piece.put("roadClass", road.roadClass());
            piece.put("lanes", road.laneCount());
            piece.put("limitKmph", l == null ? null : l.kmph());
            piece.put("limitSource", l == null ? null : l.source().name());
            piece.put("points", line);
            pieces.add(piece);
        }
        return pieces;
    }

    private List<RoadSegment> allRoads() {
        return jdbc.query(SELECT, (rs, n) -> map(rs));
    }

    /** Every stored piece of one road (for the console's map of that road and its hand-set limits). */
    public List<Map<String, Object>> pieces(String ref, String name) {
        Snapshot current = snapshot();
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(SELECT + " WHERE coalesce(road_ref, '') = coalesce(:ref, '') AND coalesce(road_name, '') = coalesce(:name, '') ORDER BY id",
                new MapSqlParameterSource("ref", ref).addValue("name", name), rs -> {
                    RoadSegment road = map(rs);
                    RoadSpeedTable.Limit l = current.table().limitFor(road);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", road.id());
                    row.put("roadClass", road.roadClass());
                    row.put("density", road.density());
                    row.put("lanes", road.laneCount());
                    row.put("stateCode", road.stateCode());
                    row.put("lengthM", Math.round(road.lengthMeters()));
                    row.put("osmLimitKmph", road.osmLimitKmph());
                    row.put("manualLimitKmph", road.manualLimitKmph());
                    row.put("limitKmph", l == null ? null : l.kmph());
                    row.put("limitSource", l == null ? null : l.source().name());
                    row.put("from", road.points().getFirst());
                    row.put("to", road.points().getLast());
                    rows.add(row);
                });
        return rows;
    }

    /** The stored pieces with these ids, with what a road pass adds up from them (Valhalla's speed, bridge, curvature). */
    public Map<Long, RoadPasses.Piece> passPieces(java.util.Collection<Long> ids) {
        Map<Long, RoadPasses.Piece> pieces = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return pieces;
        }
        jdbc.query(SELECT.replace("FROM road_segment", ", valhalla_speed_kmph, bridge, curvature FROM road_segment")
                + " WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids), rs -> {
            RoadSegment road = map(rs);
            pieces.put(road.id(), new RoadPasses.Piece(road, (Integer) rs.getObject("valhalla_speed_kmph"),
                    Boolean.TRUE.equals(rs.getObject("bridge")), (Integer) rs.getObject("curvature")));
        });
        return pieces;
    }

    private static RoadSegment map(ResultSet rs) throws SQLException {
        return new RoadSegment(rs.getLong("id"), rs.getString("segment_key"), rs.getLong("way_id"), rs.getString("road_name"),
                rs.getString("road_ref"), rs.getString("road_class"), rs.getInt("density"), rs.getInt("lane_count"),
                rs.getString("state_code"), (Integer) rs.getObject("osm_limit_kmph"), (Integer) rs.getObject("manual_limit_kmph"),
                rs.getDouble("length_m"), Polyline6.decode(rs.getString("shape")));
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
