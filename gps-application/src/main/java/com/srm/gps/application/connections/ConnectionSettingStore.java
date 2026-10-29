package com.srm.gps.application.connections;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/** The addresses saved from the console, in PostgreSQL (connection_setting). A missing row means "the default". */
@Service
public class ConnectionSettingStore {

    public static final String REDIS = "redis";
    public static final String VALHALLA = "valhalla";

    public record Saved(String url, Instant updatedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ConnectionSettingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Saved> find(String name) {
        return jdbc.query("SELECT url, updated_at FROM connection_setting WHERE name = :name",
                new MapSqlParameterSource("name", name),
                (rs, n) -> new Saved(rs.getString("url"), rs.getTimestamp("updated_at").toInstant())).stream().findFirst();
    }

    public void save(String name, String url) {
        jdbc.update("""
                INSERT INTO connection_setting (name, url, updated_at) VALUES (:name, :url, now())
                ON CONFLICT (name) DO UPDATE SET url = EXCLUDED.url, updated_at = EXCLUDED.updated_at""",
                new MapSqlParameterSource("name", name).addValue("url", url));
    }

    /** Back to the default from application.yml. */
    public void remove(String name) {
        jdbc.update("DELETE FROM connection_setting WHERE name = :name", new MapSqlParameterSource("name", name));
    }
}
