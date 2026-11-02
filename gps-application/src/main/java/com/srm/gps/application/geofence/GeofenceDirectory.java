package com.srm.gps.application.geofence;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.geofence.GeofenceIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The active geofences (table place) as one in-memory {@link GeofenceIndex}, shared by every module thread.
 *
 * Built at the first reading and rebuilt when the fences change: at once after an edit through the API
 * ({@link #reload()}), and within 30 s after any other change (another service instance, SQL by hand) - the table's
 * fingerprint (count and newest updated_at) is checked every 30 s. A rebuild makes a new index and swaps the reference,
 * so a reading being handled keeps the index it started with.
 */
@Component
public class GeofenceDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(GeofenceDirectory.class);

    private static final String SELECT = """
            SELECT id, code, name, role, shape, polygon::text AS polygon, latitude, longitude, radius_m, dwell_alert_min,
                   speed_limit_kmph
            FROM place WHERE active
            """;
    private static final String FINGERPRINT = "SELECT count(*) || '|' || coalesce(max(updated_at)::text, '') FROM place";

    private final NamedParameterJdbcTemplate jdbc;
    private volatile GeofenceIndex index;
    private volatile String fingerprint;

    public GeofenceDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The current index, loaded on first use. Throws when PostgreSQL is down and nothing was loaded yet. */
    public GeofenceIndex index() {
        GeofenceIndex current = index;
        return current != null ? current : reload();
    }

    public synchronized GeofenceIndex reload() {
        String print = jdbc.queryForObject(FINGERPRINT, new MapSqlParameterSource(), String.class);
        List<Geofence> fences = new ArrayList<>();
        jdbc.query(SELECT, rs -> {
            try {
                fences.add(map(rs));
            } catch (RuntimeException broken) {
                LOG.warn("Geofence {} ({}) left out of the index: {}", rs.getLong("id"), rs.getString("code"), broken.getMessage());
            }
        });
        GeofenceIndex built = GeofenceIndex.of(fences);
        index = built;
        fingerprint = print;
        LOG.info("Geofence index built: {}", built.stats());
        return built;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reloadIfChanged() {
        if (index == null) {
            return;
        }
        try {
            String print = jdbc.queryForObject(FINGERPRINT, new MapSqlParameterSource(), String.class);
            if (!print.equals(fingerprint)) {
                reload();
            }
        } catch (RuntimeException databaseDown) {
            LOG.debug("Geofence fingerprint not checked: {}", databaseDown.getMessage());
        }
    }

    private static Geofence map(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Integer dwell = (Integer) rs.getObject("dwell_alert_min");
        Integer speed = (Integer) rs.getObject("speed_limit_kmph");
        if ("POLYGON".equals(rs.getString("shape"))) {
            return Geofence.polygon(id, rs.getString("code"), rs.getString("name"), rs.getString("role"),
                    corners(rs.getString("polygon")), dwell, speed);
        }
        return Geofence.circle(id, rs.getString("code"), rs.getString("name"), rs.getString("role"), rs.getDouble("latitude"),
                rs.getDouble("longitude"), rs.getInt("radius_m"), dwell, speed);
    }

    /** "[[22.81, 86.07], [22.82, 86.08], ...]" -> corners */
    static List<double[]> corners(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return Arrays.asList(GpsMessageJson.fromJson(json, double[][].class));
    }

    /** For tests and the API: the fences as the index holds them, by id. */
    public Map<Long, Geofence> byId() {
        Map<Long, Geofence> all = new java.util.LinkedHashMap<>();
        index().fences().forEach(f -> all.put(f.id(), f));
        return all;
    }
}
