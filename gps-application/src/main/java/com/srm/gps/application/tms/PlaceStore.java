package com.srm.gps.application.tms;

import com.srm.gps.processing.shipment.Fence;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The geofences shipments start and end in (table place). A shipment file names its origin and destination in words
 * ("JAMSHEDPUR", "ADITYAPUR"); the place whose code is that name, normalised, is its fence. Kept in memory: the
 * tracking module asks for a fence with every reading, and the list changes only when someone edits it.
 */
@Component
public class PlaceStore {

    public enum Role { ORIGIN, DESTINATION }

    public record Place(long id, String code, String name, Role role, double latitude, double longitude, int radiusMeters,
                        String source, boolean active) {
        public Fence fence() {
            return new Fence(latitude, longitude, radiusMeters);
        }
    }

    private static final String SELECT = "SELECT id, code, name, role, latitude, longitude, radius_m, source, active FROM place";

    private final NamedParameterJdbcTemplate jdbc;
    private volatile Map<Long, Place> byId;

    public PlaceStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Place> list() {
        return places().values().stream().sorted((a, b) -> a.code().compareTo(b.code())).toList();
    }

    public Optional<Place> byId(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(places().get(id));
    }

    /** The active place with this name, preferring the given role (a city can be both a start and an end). */
    public Optional<Place> resolve(String name, Role preferred) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String code = code(name);
        Place other = null;
        for (Place place : places().values()) {
            if (place.active() && place.code().equals(code)) {
                if (place.role() == preferred) {
                    return Optional.of(place);
                }
                other = place;
            }
        }
        return Optional.ofNullable(other);
    }

    public Place save(String name, Role role, double latitude, double longitude, int radiusMeters) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A place needs a name, as the shipment files write it");
        }
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || (latitude == 0 && longitude == 0)) {
            throw new IllegalArgumentException("That is not a position on the map");
        }
        if (radiusMeters < 50 || radiusMeters > 50_000) {
            throw new IllegalArgumentException("A geofence radius is 50 m to 50 km");
        }
        jdbc.update("""
                INSERT INTO place (code, name, role, latitude, longitude, radius_m, source)
                VALUES (:code, :name, :role, :lat, :lon, :radius, 'MANUAL')
                ON CONFLICT (code, role) DO UPDATE SET name = EXCLUDED.name, latitude = EXCLUDED.latitude,
                    longitude = EXCLUDED.longitude, radius_m = EXCLUDED.radius_m, active = true, updated_at = now()
                """, new MapSqlParameterSource("code", code(name)).addValue("name", name.trim()).addValue("role", role.name())
                .addValue("lat", latitude).addValue("lon", longitude).addValue("radius", radiusMeters));
        byId = null;
        return resolve(name, role).orElseThrow();
    }

    public void deactivate(long id) {
        jdbc.update("UPDATE place SET active = false, updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", id));
        byId = null;
    }

    /** "  Jamshedpur  (IBMD) " -> "JAMSHEDPUR (IBMD)" */
    public static String code(String name) {
        return name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private Map<Long, Place> places() {
        Map<Long, Place> loaded = byId;
        if (loaded == null) {
            loaded = jdbc.query(SELECT, new MapSqlParameterSource(), (rs, n) -> map(rs)).stream()
                    .collect(Collectors.toUnmodifiableMap(Place::id, Function.identity()));
            byId = loaded;
        }
        return loaded;
    }

    private static Place map(ResultSet rs) throws SQLException {
        return new Place(rs.getLong("id"), rs.getString("code"), rs.getString("name"), Role.valueOf(rs.getString("role")),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getInt("radius_m"), rs.getString("source"), rs.getBoolean("active"));
    }
}
