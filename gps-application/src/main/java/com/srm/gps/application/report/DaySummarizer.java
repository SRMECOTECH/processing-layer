package com.srm.gps.application.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds vehicle_day_summary: one row per vehicle per day, from the raw tables, in one SQL statement per day.
 *
 *   readings, distance, max speed     gps_position of the day (distance = the processing step's distance_from_previous_m)
 *   driving / standing time           gaps between readings, by the later reading's movement status (MOVING / STOPPED);
 *                                     a driving gap longer than gps.reports.max-gap-seconds (10 min) counts as neither,
 *                                     a standing one up to max-stopped-gap-seconds (1 h: a parked tracker reports rarely)
 *   km above the economical speed     distance of readings faster than the cost assumption's economical speed
 *   idling                            union of the IDLING episodes (engine on, standing past the idling limit) between
 *                                     the day's first and last reading, never more than the standing time
 *   stops, trips                      gps_stop / gps_trip that started that day; stop time cut to the day; a stop has
 *                                     the engine on when a STOPPAGE or IDLING episode overlaps it
 *   over-speed, harsh                 vehicle_event OVERSPEED, HARSH_* that started that day
 *
 * Only generation 0 (the live rows) is rebuilt. Every method is synchronized: the job and "Clear database" never
 * run at the same time.
 */
@Service
public class DaySummarizer {

    private static final Logger LOG = LoggerFactory.getLogger(DaySummarizer.class);

    private static final String SUMMARIZE = """
            WITH pos AS (
                SELECT imei,
                       max(tenant_code) AS tenant_code,
                       max(vehicle_registration_no) AS vehicle_registration_no,
                       count(*) AS readings,
                       min(recorded_at) AS first_seen_at,
                       max(recorded_at) AS last_seen_at,
                       coalesce(sum(distance_from_previous_m), 0) AS distance_m,
                       coalesce(sum(seconds_since_previous) FILTER (WHERE movement_status = 'MOVING'
                                AND seconds_since_previous BETWEEN 0 AND :maxGap), 0) AS moving_s,
                       coalesce(sum(seconds_since_previous) FILTER (WHERE movement_status = 'STOPPED'
                                AND seconds_since_previous BETWEEN 0 AND :maxStoppedGap), 0) AS stopped_s,
                       coalesce(max(speed_kmph), 0) AS max_speed_kmph,
                       coalesce(sum(distance_from_previous_m) FILTER (WHERE speed_kmph > :eco), 0) AS fast_distance_m
                FROM gps_position
                WHERE recorded_at >= :from AND recorded_at < :to
                GROUP BY imei),
            ev AS (
                SELECT imei,
                       count(*) FILTER (WHERE event_type = 'OVERSPEED') AS overspeed_events,
                       count(*) FILTER (WHERE event_type IN ('HARSH_ACCELERATION', 'HARSH_BRAKING')) AS harsh_events
                FROM vehicle_event
                WHERE started_at >= :from AND started_at < :to
                GROUP BY imei),
            -- idling: the UNION of the IDLING episodes (overlapping episodes count once), only between the vehicle's
            -- first and last reading of the day: nothing is known before or after them
            idle_ranges AS (
                SELECT e.imei,
                       range_agg(tstzrange(greatest(e.started_at, :from, p.first_seen_at),
                                           least(coalesce(e.ended_at, p.last_seen_at), :to, p.last_seen_at))) AS ranges
                FROM vehicle_event e
                JOIN pos p USING (imei)
                WHERE e.event_type = 'IDLING'
                  AND greatest(e.started_at, :from, p.first_seen_at) < least(coalesce(e.ended_at, p.last_seen_at), :to, p.last_seen_at)
                GROUP BY e.imei),
            idle AS (
                SELECT imei, coalesce(sum(EXTRACT(EPOCH FROM upper(r) - lower(r))), 0) AS idle_s
                FROM idle_ranges, unnest(ranges) AS r
                GROUP BY imei),
            -- stops from the trip detector; "engine on" when a STOPPAGE or IDLING episode overlaps the stop
            st AS (
                SELECT s.imei,
                       count(*) FILTER (WHERE s.start_recorded_at >= :from) AS stops,
                       count(*) FILTER (WHERE s.start_recorded_at >= :from AND EXISTS (
                           SELECT 1 FROM vehicle_event e
                           WHERE e.imei = s.imei AND e.event_type IN ('STOPPAGE', 'IDLING')
                             AND e.started_at < s.end_recorded_at
                             AND coalesce(e.ended_at, s.end_recorded_at) > s.start_recorded_at)) AS engine_on_stops,
                       coalesce(sum(EXTRACT(EPOCH FROM least(s.end_recorded_at, :to) - greatest(s.start_recorded_at, :from))), 0) AS stop_s
                FROM gps_stop s
                WHERE s.start_recorded_at < :to AND s.end_recorded_at > :from
                GROUP BY s.imei),
            tr AS (
                SELECT imei, count(*) AS trips
                FROM gps_trip
                WHERE start_recorded_at >= :from AND start_recorded_at < :to
                GROUP BY imei)
            INSERT INTO vehicle_day_summary (day, imei, generation, tenant_code, vehicle_registration_no, readings,
                    first_seen_at, last_seen_at, distance_m, moving_s, stopped_s, max_speed_kmph, fast_distance_m,
                    economical_speed_kmph, idle_s, stops, stop_s, engine_on_stops, trips, overspeed_events, harsh_events, computed_at)
            SELECT :day, pos.imei, 0, pos.tenant_code, pos.vehicle_registration_no, pos.readings,
                   pos.first_seen_at, pos.last_seen_at, pos.distance_m, pos.moving_s, pos.stopped_s, pos.max_speed_kmph,
                   pos.fast_distance_m, :eco, least(greatest(coalesce(idle.idle_s, 0), 0), pos.stopped_s)::bigint, coalesce(st.stops, 0),
                   greatest(coalesce(st.stop_s, 0), 0)::bigint, coalesce(st.engine_on_stops, 0), coalesce(tr.trips, 0),
                   coalesce(ev.overspeed_events, 0), coalesce(ev.harsh_events, 0), now()
            FROM pos
            LEFT JOIN ev USING (imei)
            LEFT JOIN idle USING (imei)
            LEFT JOIN st USING (imei)
            LEFT JOIN tr USING (imei)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ReportSettingStore settings;
    private final ZoneId zone;
    private final long maxGapSeconds;
    private final long maxStoppedGapSeconds;

    public DaySummarizer(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction, ReportSettingStore settings,
                         ReportProperties properties) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.settings = settings;
        this.zone = properties.zoneOrDefault();
        this.maxGapSeconds = properties.maxGapSecondsOrDefault();
        this.maxStoppedGapSeconds = properties.maxStoppedGapSecondsOrDefault();
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Rebuilds the live rows of one day. Returns how many vehicles had readings that day. */
    public synchronized int summarize(LocalDate day) {
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("day", day)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("maxGap", maxGapSeconds)
                .addValue("maxStoppedGap", maxStoppedGapSeconds)
                .addValue("eco", settings.assumptions().economicalSpeedKmph());
        Integer rows = transaction.execute(status -> {
            jdbc.update("DELETE FROM vehicle_day_summary WHERE day = :day AND generation = 0", params);
            return jdbc.update(SUMMARIZE, params);
        });
        return rows == null ? 0 : rows;
    }

    /** Days, oldest first, that still have positions in PostgreSQL, at most {@code maxDays} back from today. */
    public synchronized List<LocalDate> daysWithPositions(int maxDays) {
        List<LocalDate> days = new ArrayList<>();
        jdbc.query("SELECT min(recorded_at) AS oldest, max(recorded_at) AS newest FROM gps_position", rs -> {
            Timestamp oldest = rs.getTimestamp("oldest");
            Timestamp newest = rs.getTimestamp("newest");
            if (oldest == null || newest == null) {
                return;
            }
            LocalDate first = oldest.toInstant().atZone(zone).toLocalDate();
            LocalDate last = newest.toInstant().atZone(zone).toLocalDate();
            LocalDate floor = today().minusDays(maxDays);
            for (LocalDate day = first.isBefore(floor) ? floor : first; !day.isAfter(last); day = day.plusDays(1)) {
                days.add(day);
            }
        });
        return days;
    }

    /** Rebuilds every day that still has positions (at most {@code maxDays} back). Returns the number of days. */
    public synchronized int summarizeAll(int maxDays) {
        List<LocalDate> days = daysWithPositions(maxDays);
        for (LocalDate day : days) {
            summarize(day);
        }
        return days.size();
    }

    /**
     * "Clear database" keeping the reports: rebuilds the live rows of every day that still has data, freezes them into a
     * new generation, then runs {@code emptyTables} - all while the job is kept out. Returns what emptyTables returns.
     */
    public synchronized <T> T freezeThen(int maxDays, Supplier<T> emptyTables) {
        int days = summarizeAll(maxDays);
        int frozen = jdbc.update("""
                UPDATE vehicle_day_summary
                SET generation = (SELECT coalesce(max(generation), 0) + 1 FROM vehicle_day_summary)
                WHERE generation = 0""", new MapSqlParameterSource());
        LOG.info("Reports kept: {} vehicle-days of {} days frozen before the database is emptied", frozen, days);
        return emptyTables.get();
    }

    /** "Clear database" without keeping the reports. */
    public synchronized long deleteAll() {
        return jdbc.update("DELETE FROM vehicle_day_summary", new MapSqlParameterSource());
    }
}
