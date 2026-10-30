package com.srm.gps.connectivity.smarttruck;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;

/**
 * Reads GPS pings from the existing smart_truck MySQL database.
 *
 * READ-ONLY by design:
 *  - the connection pool is opened in read-only mode, so MySQL itself refuses any INSERT/UPDATE/DELETE;
 *  - this class only contains SELECT statements.
 *
 * The pool is created here (not as a Spring DataSource bean) so it can never be mistaken for
 * the application's main database, which is the new gps_tracking PostgreSQL database.
 */
@Component
public class SmartTruckGpsReader {

    /** smart_truck stores dt_message as local India time without a zone. */
    private static final ZoneId SMART_TRUCK_TIME_ZONE = ZoneId.of("Asia/Kolkata");

    /** The newest trip of each device - so N trips means N different devices driving at the same time. */
    private static final String LATEST_TRIP_OF_DISTINCT_DEVICES = """
            SELECT MAX(i_trip_no) AS latest_trip_no
            FROM tta_trips
            WHERE i_gps_ping_count >= ?
              AND s_device_id IS NOT NULL AND s_device_id <> ''
              AND s_asset_id IS NOT NULL AND s_asset_id <> ''
            GROUP BY s_device_id
            ORDER BY latest_trip_no DESC
            LIMIT ?
            """;

    private static final String PINGS_OF_ONE_TRIP = """
            SELECT i_trip_no, s_asset_id, s_device_id, s_entity_name, dt_message, d_lat, d_long, i_speed, i_dist
            FROM tta_trip_gps
            WHERE i_trip_no = ?
            ORDER BY dt_message
            LIMIT ?
            """;

    private final HikariDataSource readOnlyConnectionPool;
    private final JdbcTemplate jdbcTemplate;

    public SmartTruckGpsReader(SmartTruckDatabaseSettings settings) {
        HikariConfig poolConfiguration = new HikariConfig();
        poolConfiguration.setPoolName("smart-truck-read-only");
        poolConfiguration.setJdbcUrl(settings.url());
        poolConfiguration.setUsername(settings.username());
        poolConfiguration.setPassword(settings.password());
        poolConfiguration.setReadOnly(true);
        poolConfiguration.setMaximumPoolSize(2);
        // Do not fail application start-up when smart_truck is down; fail only when it is actually read.
        poolConfiguration.setInitializationFailTimeout(-1);

        this.readOnlyConnectionPool = new HikariDataSource(poolConfiguration);
        this.jdbcTemplate = new JdbcTemplate(readOnlyConnectionPool);
    }

    /** Trip numbers of the newest trip of {@code deviceCount} different devices, each with at least {@code minimumPings} pings. */
    public List<Long> findLatestTripOfDistinctDevices(int deviceCount, int minimumPings) {
        return jdbcTemplate.queryForList(LATEST_TRIP_OF_DISTINCT_DEVICES, Long.class, minimumPings, deviceCount);
    }

    /**
     * The TMS rows (tta_trips) of these trips, every column, keyed by column name: the shipments behind the trips the
     * simulator replays. Times are the database's own (India time, no zone).
     */
    public List<java.util.Map<String, Object>> readTripRows(List<Long> tripNumbers) {
        if (tripNumbers.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(tripNumbers.size(), "?"));
        return jdbcTemplate.queryForList("SELECT * FROM tta_trips WHERE i_trip_no IN (" + placeholders + ")", tripNumbers.toArray());
    }

    /** The first {@code maximumPings} pings of one trip, oldest first. */
    public List<SmartTruckGpsPing> readPingsOfTrip(long tripNumber, int maximumPings) {
        return jdbcTemplate.query(PINGS_OF_ONE_TRIP, this::toGpsPing, tripNumber, maximumPings);
    }

    private SmartTruckGpsPing toGpsPing(ResultSet row, int rowNumber) throws SQLException {
        return new SmartTruckGpsPing(
                row.getLong("i_trip_no"),
                row.getString("s_asset_id"),
                row.getString("s_device_id"),
                row.getString("s_entity_name"),
                row.getTimestamp("dt_message").toLocalDateTime().atZone(SMART_TRUCK_TIME_ZONE).toInstant(),
                row.getDouble("d_lat"),
                row.getDouble("d_long"),
                row.getInt("i_speed"),
                row.getInt("i_dist"));
    }

    @PreDestroy
    public void closeConnectionPool() {
        readOnlyConnectionPool.close();
    }
}
