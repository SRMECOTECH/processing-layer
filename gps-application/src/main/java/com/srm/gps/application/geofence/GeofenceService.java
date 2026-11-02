package com.srm.gps.application.geofence;

import com.srm.gps.application.tms.PlaceStore;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsReader;
import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.geofence.GeofenceIndex;
import com.srm.gps.processing.geofence.GeofenceSettings;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The geofence master (table place) and the live picture: what the console's Geofencing pages and the API show.
 *
 * Writing a fence rebuilds the module's index at once ({@link GeofenceDirectory#reload()}), so the next reading is
 * already checked against it.
 */
@Service
public class GeofenceService {

    private static final Set<String> ROLES = Set.of("ORIGIN", "DESTINATION", "YARD", "CHECKPOINT", "ZONE");

    private final NamedParameterJdbcTemplate jdbc;
    private final GeofenceDirectory directory;
    private final GeofenceVisitStore visits;
    private final PlaceStore places;
    private final SmartTruckGpsReader smartTruck;
    private final GeofenceModule module;

    public GeofenceService(NamedParameterJdbcTemplate jdbc, GeofenceDirectory directory, GeofenceVisitStore visits, PlaceStore places,
                           SmartTruckGpsReader smartTruck, GeofenceModule module) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.visits = visits;
        this.places = places;
        this.smartTruck = smartTruck;
        this.module = module;
    }

    /** A fence as the console edits it. polygon: [[lat, lon], ...] for a POLYGON, else null. */
    public record FenceChange(String name, String role, String shape, Double latitude, Double longitude, Integer radiusMeters,
                              List<double[]> polygon, Integer dwellAlertMinutes, Integer speedLimitKmph) {
    }

    /** Every fence (active or not), with the vehicles inside it now. */
    public List<Map<String, Object>> fences() {
        Map<Long, Integer> inside = visits.insideCounts();
        GeofenceSettings settings = module.settings();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, code, name, role, shape, polygon::text AS polygon, latitude, longitude, radius_m, dwell_alert_min,
                       speed_limit_kmph, source, source_detail, external_ref, trips_seen, spread_m, active, updated_at
                FROM place ORDER BY code, role
                """, new MapSqlParameterSource());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> f = new LinkedHashMap<>();
            long id = ((Number) r.get("id")).longValue();
            f.put("id", id);
            f.put("code", r.get("code"));
            f.put("name", r.get("name"));
            f.put("role", r.get("role"));
            f.put("shape", r.get("shape"));
            f.put("polygon", r.get("polygon") == null ? null : GeofenceDirectory.corners((String) r.get("polygon")));
            f.put("latitude", r.get("latitude"));
            f.put("longitude", r.get("longitude"));
            f.put("radiusMeters", r.get("radius_m"));
            f.put("dwellAlertMinutes", r.get("dwell_alert_min"));
            f.put("dwellAlertMinutesInForce", directory.index().byId(id).map(settings::dwellAlertMinutes).orElse(null));
            f.put("speedLimitKmph", r.get("speed_limit_kmph"));
            f.put("source", r.get("source"));
            f.put("sourceDetail", r.get("source_detail"));
            f.put("externalRef", r.get("external_ref"));
            f.put("tripsSeen", r.get("trips_seen"));
            f.put("spreadMeters", r.get("spread_m"));
            f.put("active", r.get("active"));
            f.put("updatedAt", r.get("updated_at"));
            f.put("insideNow", inside.getOrDefault(id, 0));
            out.add(f);
        }
        return out;
    }

    /** The index the module uses, and its rules: for "How it is calculated". */
    public Map<String, Object> engine() {
        GeofenceIndex index = directory.index();
        GeofenceSettings s = module.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index.stats());
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("confirmMinutes", s.confirmSeconds() / 60);
        rules.put("escapeFactor", s.escapeFactor());
        rules.put("passMinTravelMeters", s.passMinTravelMeters());
        rules.put("crossingMaxGapMinutes", s.crossingMaxGapSeconds() / 60);
        rules.put("crossingMinMoveMeters", s.crossingMinMoveMeters());
        rules.put("defaultDwellAlertMinutes", s.defaultDwellAlertMinutes());
        rules.put("dwellAlertMinutesByRole", s.dwellAlertMinutesByRole());
        out.put("rules", rules);
        return out;
    }

    /** Which fences a point is inside (a quick check from the console or curl). */
    public List<Map<String, Object>> locate(double latitude, double longitude) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Geofence f : directory.index().locate(latitude, longitude)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("code", f.code());
            m.put("name", f.name());
            m.put("role", f.role());
            m.put("metersFromCentre", Math.round(f.distanceFromCentreMeters(latitude, longitude)));
            out.add(m);
        }
        return out;
    }

    /** Creates or changes a fence (by code + role); a fence written here is MANUAL and never overwritten by an import. */
    public Map<String, Object> save(String code, FenceChange c) {
        String normalised = PlaceStore.code(code == null || code.isBlank() ? String.valueOf(c.name()) : code);
        String role = c.role() == null ? "DESTINATION" : c.role().trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("Role is one of " + ROLES);
        }
        String shape = c.shape() == null ? "CIRCLE" : c.shape().trim().toUpperCase(Locale.ROOT);
        Geofence check;
        double lat;
        double lon;
        int radius;
        String polygon = null;
        if ("POLYGON".equals(shape)) {
            check = Geofence.polygon(0, normalised, c.name(), role, c.polygon(), null, null);
            polygon = GpsMessageJson.toJson(c.polygon());
            lat = check.latitude();
            lon = check.longitude();
            radius = (int) Math.ceil(check.radiusMeters());
        } else if ("CIRCLE".equals(shape)) {
            if (c.latitude() == null || c.longitude() == null || c.radiusMeters() == null) {
                throw new IllegalArgumentException("A circle needs latitude, longitude and radiusMeters");
            }
            lat = c.latitude();
            lon = c.longitude();
            radius = c.radiusMeters();
            if (radius < 50 || radius > 50_000) {
                throw new IllegalArgumentException("A circle's radius is 50 m to 50 km");
            }
            check = Geofence.circle(0, normalised, c.name(), role, lat, lon, radius, null, null);
        } else {
            throw new IllegalArgumentException("Shape is CIRCLE or POLYGON");
        }
        if (!insideIndia(check.latitude(), check.longitude())) {
            throw new IllegalArgumentException("That is not in India - latitude and longitude the wrong way round?");
        }
        jdbc.update("""
                INSERT INTO place (code, name, role, shape, polygon, latitude, longitude, radius_m, dwell_alert_min, speed_limit_kmph, source)
                VALUES (:code, :name, :role, :shape, CAST(:polygon AS jsonb), :lat, :lon, :radius, :dwell, :speed, 'MANUAL')
                ON CONFLICT (code, role) DO UPDATE SET name = EXCLUDED.name, shape = EXCLUDED.shape, polygon = EXCLUDED.polygon,
                    latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, radius_m = EXCLUDED.radius_m,
                    dwell_alert_min = EXCLUDED.dwell_alert_min, speed_limit_kmph = EXCLUDED.speed_limit_kmph,
                    source = 'MANUAL', active = true, updated_at = now()
                """, new MapSqlParameterSource("code", normalised).addValue("name", c.name() == null || c.name().isBlank() ? normalised : c.name().trim())
                .addValue("role", role).addValue("shape", shape).addValue("polygon", polygon).addValue("lat", lat).addValue("lon", lon)
                .addValue("radius", radius).addValue("dwell", c.dwellAlertMinutes()).addValue("speed", c.speedLimitKmph()));
        changed();
        return fences().stream().filter(f -> normalised.equals(f.get("code")) && role.equals(f.get("role"))).findFirst().orElseThrow();
    }

    public void setActive(long id, boolean active) {
        int rows = jdbc.update("UPDATE place SET active = :active, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("active", active));
        if (rows == 0) {
            throw new IllegalArgumentException("No geofence " + id);
        }
        changed();
    }

    /**
     * Copies smart_truck's geofence master (read-only MySQL) into place: new fences are added, fences that came from
     * smart_truck before are updated, fences edited here (MANUAL) are left alone and reported.
     */
    public Map<String, Object> importFromSmartTruck() {
        List<Map<String, Object>> rows = smartTruck.readGeofences();
        int added = 0;
        int updated = 0;
        List<String> keptManual = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String code = PlaceStore.code(String.valueOf(r.get("s_key")));
            String role = String.valueOf(r.get("s_role")).trim().toUpperCase(Locale.ROOT);
            double lat = number(r.get("d_lat"));
            double lon = number(r.get("d_long"));
            int radius = (int) number(r.get("i_radius_m"));
            if (!ROLES.contains(role) || !insideIndia(lat, lon) || radius < 50 || radius > 50_000) {
                refused.add(code + " (" + role + ")");
                continue;
            }
            String source = jdbc.query("SELECT source FROM place WHERE code = :code AND role = :role",
                    new MapSqlParameterSource("code", code).addValue("role", role), rs -> rs.next() ? rs.getString(1) : null);
            if ("MANUAL".equals(source)) {
                keptManual.add(code + " (" + role + ")");
                continue;
            }
            jdbc.update("""
                    INSERT INTO place (code, name, role, latitude, longitude, radius_m, source, active, external_ref, source_detail,
                        trips_seen, spread_m)
                    VALUES (:code, :name, :role, :lat, :lon, :radius, 'SMART_TRUCK', :active, :ref, :detail, :trips, :spread)
                    ON CONFLICT (code, role) DO UPDATE SET name = EXCLUDED.name, latitude = EXCLUDED.latitude,
                        longitude = EXCLUDED.longitude, radius_m = EXCLUDED.radius_m, active = EXCLUDED.active,
                        external_ref = EXCLUDED.external_ref, source_detail = EXCLUDED.source_detail, trips_seen = EXCLUDED.trips_seen,
                        spread_m = EXCLUDED.spread_m, updated_at = now()
                    WHERE place.source <> 'MANUAL'
                    """, new MapSqlParameterSource("code", code).addValue("name", String.valueOf(r.get("s_name")).trim()).addValue("role", role)
                    .addValue("lat", lat).addValue("lon", lon).addValue("radius", radius).addValue("active", truthy(r.get("b_active")))
                    .addValue("ref", "smart_truck:" + r.get("i_fence_id")).addValue("detail", r.get("s_source"))
                    .addValue("trips", r.get("i_trips_seen")).addValue("spread", r.get("i_spread_m")));
            if (source == null) {
                added++;
            } else {
                updated++;
            }
        }
        changed();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("read", rows.size());
        report.put("added", added);
        report.put("updated", updated);
        report.put("keptBecauseEditedHere", keptManual);
        report.put("refused", refused);
        report.put("at", Instant.now());
        return report;
    }

    private void changed() {
        directory.reload();
        places.invalidate();
    }

    /** India's bounding box, with a margin: catches latitude and longitude swapped (a point in Somalia). */
    static boolean insideIndia(double lat, double lon) {
        return lat >= 6 && lat <= 37.5 && lon >= 68 && lon <= 98;
    }

    private static double number(Object value) {
        if (value instanceof BigDecimal b) {
            return b.doubleValue();
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
