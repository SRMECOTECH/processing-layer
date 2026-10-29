package com.srm.gps.application.report;

import com.srm.gps.application.report.ReportViews.DayRow;
import com.srm.gps.application.report.ReportViews.EventEntry;
import com.srm.gps.application.report.ReportViews.Filters;
import com.srm.gps.application.report.ReportViews.FleetReport;
import com.srm.gps.application.report.ReportViews.TenantOption;
import com.srm.gps.application.report.ReportViews.TimelineEntry;
import com.srm.gps.application.report.ReportViews.VehicleDaySheet;
import com.srm.gps.application.report.ReportViews.VehicleOption;
import com.srm.gps.application.report.ReportViews.VehicleRow;
import com.srm.gps.processing.report.FuelAssumptions;
import com.srm.gps.processing.report.FuelEstimate;
import com.srm.gps.processing.report.FuelEstimator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reports, read from vehicle_day_summary (a few thousand rows a month) and priced with the cost assumptions at
 * the moment of reading: a new fuel price changes every report at once. Only the vehicle day sheet reads raw tables,
 * and only one vehicle's day of them.
 */
@Service
public class ReportService {

    /** The generations of a day are added up: live rows plus the ones frozen by "Clear database". */
    private static final String SUMMED = """
            SELECT %1$s,
                   max(tenant_code) AS tenant_code, max(vehicle_registration_no) AS vehicle_registration_no,
                   count(DISTINCT imei) AS vehicles, count(DISTINCT day) AS days, sum(readings) AS readings,
                   min(first_seen_at) AS first_seen_at, max(last_seen_at) AS last_seen_at,
                   sum(distance_m) AS distance_m, sum(moving_s) AS moving_s, sum(stopped_s) AS stopped_s,
                   max(max_speed_kmph) AS max_speed_kmph, sum(fast_distance_m) AS fast_distance_m,
                   max(economical_speed_kmph) AS economical_speed_kmph, sum(idle_s) AS idle_s, sum(stops) AS stops,
                   sum(stop_s) AS stop_s, sum(engine_on_stops) AS engine_on_stops, sum(trips) AS trips,
                   sum(overspeed_events) AS overspeed_events, sum(harsh_events) AS harsh_events, max(computed_at) AS computed_at
            FROM vehicle_day_summary
            WHERE day >= :from AND day <= :to
              AND (CAST(:tenant AS varchar) IS NULL OR tenant_code = :tenant)
              AND (CAST(:imei AS varchar) IS NULL OR imei = :imei)
            GROUP BY %1$s
            ORDER BY %1$s""";

    private final NamedParameterJdbcTemplate jdbc;
    private final DaySummarizer summarizer;
    private final ReportSettingStore settings;

    public ReportService(NamedParameterJdbcTemplate jdbc, DaySummarizer summarizer, ReportSettingStore settings) {
        this.jdbc = jdbc;
        this.summarizer = summarizer;
        this.settings = settings;
    }

    public FleetReport day(LocalDate day, String tenant, String imei) {
        LocalDate date = day != null ? day : summarizer.today();
        return report("day", date, date, tenant, imei, false);
    }

    public FleetReport month(YearMonth month, String tenant, String imei) {
        YearMonth m = month != null ? month : YearMonth.from(summarizer.today());
        return report("month", m.atDay(1), m.atEndOfMonth(), tenant, imei, true);
    }

    private FleetReport report(String kind, LocalDate from, LocalDate to, String tenant, String imei, boolean withDays) {
        FuelAssumptions assumptions = settings.assumptions();
        MapSqlParameterSource params = params(from, to, tenant, imei);
        List<VehicleRow> vehicles = new ArrayList<>(jdbc.query(SUMMED.formatted("imei"), params,
                (rs, n) -> row(rs, rs.getString("imei"), assumptions)));
        vehicles.sort(Comparator.comparingDouble((VehicleRow v) -> v.fuel().savableCost()).reversed()
                .thenComparing(v -> v.registration() == null ? v.imei() : v.registration()));
        List<DayRow> days = !withDays ? List.of() : jdbc.query(SUMMED.formatted("day"), params, (rs, n) -> {
            VehicleRow r = row(rs, null, assumptions);
            return new DayRow(rs.getDate("day").toLocalDate(), r.vehicles(), r.distanceKm(), r.movingSeconds(), r.idleSeconds(),
                    r.stops(), r.trips(), r.overspeedEvents(), r.fuel());
        });
        Instant summarizedAt = vehicles.stream().map(VehicleRow::lastSeen).filter(t -> t != null).max(Comparator.naturalOrder()).orElse(null);
        Instant computed = jdbc.query("SELECT max(computed_at) AS at FROM vehicle_day_summary WHERE day >= :from AND day <= :to", params,
                rs -> rs.next() && rs.getTimestamp("at") != null ? rs.getTimestamp("at").toInstant() : summarizedAt);
        return new FleetReport(kind, from, to, summarizer.zone().getId(), blankToNull(tenant), blankToNull(imei), assumptions,
                computed, totals(vehicles, assumptions), vehicles, days);
    }

    /** What one vehicle did on one day: the summary, its trips and stops in time order, and its events. */
    public VehicleDaySheet vehicleDay(String imei, LocalDate day) {
        if (imei == null || imei.isBlank()) {
            throw new IllegalArgumentException("Choose a vehicle");
        }
        LocalDate date = day != null ? day : summarizer.today();
        FuelAssumptions assumptions = settings.assumptions();
        MapSqlParameterSource params = params(date, date, null, imei.trim());
        VehicleRow summary = jdbc.query(SUMMED.formatted("imei"), params, (rs, n) -> row(rs, rs.getString("imei"), assumptions))
                .stream().findFirst().orElse(null);

        Instant from = date.atStartOfDay(summarizer.zone()).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(summarizer.zone()).toInstant();
        params.addValue("start", Timestamp.from(from)).addValue("end", Timestamp.from(to));

        List<EventEntry> events = jdbc.query("""
                SELECT event_type, severity, status, started_at, ended_at FROM vehicle_event
                WHERE imei = :imei AND started_at < :end AND coalesce(ended_at, started_at) >= :start
                ORDER BY started_at LIMIT 1000""", params, (rs, n) -> {
            Instant started = rs.getTimestamp("started_at").toInstant();
            Timestamp ended = rs.getTimestamp("ended_at");
            return new EventEntry(rs.getString("event_type"), rs.getString("severity"), rs.getString("status"), started,
                    ended == null ? null : ended.toInstant(), ended == null ? null : Math.max(0, ended.toInstant().getEpochSecond() - started.getEpochSecond()));
        });

        List<TimelineEntry> timeline = new ArrayList<>(jdbc.query("""
                SELECT start_recorded_at, end_recorded_at, distance_m, duration_s, max_speed_kmph, avg_speed_kmph,
                       end_latitude, end_longitude, status
                FROM gps_trip WHERE imei = :imei AND start_recorded_at < :end AND end_recorded_at >= :start
                ORDER BY start_recorded_at""", params, (rs, n) -> new TimelineEntry("TRIP",
                rs.getTimestamp("start_recorded_at").toInstant(), rs.getTimestamp("end_recorded_at").toInstant(),
                rs.getLong("duration_s"), rs.getDouble("distance_m") / 1000.0, rs.getInt("max_speed_kmph"),
                rs.getDouble("avg_speed_kmph"), rs.getDouble("end_latitude"), rs.getDouble("end_longitude"), null, 0,
                rs.getString("status"))));
        timeline.addAll(jdbc.query("""
                SELECT start_recorded_at, end_recorded_at, duration_s, latitude, longitude, status
                FROM gps_stop WHERE imei = :imei AND start_recorded_at < :end AND end_recorded_at >= :start
                ORDER BY start_recorded_at""", params, (rs, n) -> {
            Instant start = rs.getTimestamp("start_recorded_at").toInstant();
            Instant end = rs.getTimestamp("end_recorded_at").toInstant();
            return new TimelineEntry("STOP", start, end, rs.getLong("duration_s"), null, null, null, rs.getDouble("latitude"),
                    rs.getDouble("longitude"), engineDuring(events, start, end), idleDuring(events, start, end), rs.getString("status"));
        }));
        timeline.sort(Comparator.comparing(TimelineEntry::start));
        return new VehicleDaySheet(date, summarizer.zone().getId(), imei.trim(), summary, timeline, events, assumptions);
    }

    public Filters filters() {
        List<TenantOption> tenants = jdbc.query("SELECT code, legal_name FROM tenant ORDER BY legal_name",
                new MapSqlParameterSource(), (rs, n) -> new TenantOption(rs.getString("code"), rs.getString("legal_name")));
        List<VehicleOption> vehicles = jdbc.query("""
                SELECT imei, max(vehicle_registration_no) AS registration, max(tenant_code) AS tenant
                FROM vehicle_day_summary WHERE day >= :since
                GROUP BY imei ORDER BY max(vehicle_registration_no) NULLS LAST, imei""",
                new MapSqlParameterSource("since", summarizer.today().minusDays(120)),
                (rs, n) -> new VehicleOption(rs.getString("imei"), rs.getString("registration"), rs.getString("tenant")));
        return new Filters(summarizer.zone().getId(), summarizer.today(), tenants, vehicles);
    }

    public ReportViews.Refresh refresh(LocalDate day) {
        LocalDate date = day != null ? day : summarizer.today();
        return new ReportViews.Refresh(date, summarizer.summarize(date), Instant.now());
    }

    // ---- helpers ----

    private static MapSqlParameterSource params(LocalDate from, LocalDate to, String tenant, String imei) {
        return new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("tenant", blankToNull(tenant))
                .addValue("imei", blankToNull(imei));
    }

    private static VehicleRow row(ResultSet rs, String imei, FuelAssumptions a) throws SQLException {
        double distanceKm = rs.getDouble("distance_m") / 1000.0;
        long moving = rs.getLong("moving_s");
        long stopped = rs.getLong("stopped_s");
        long idle = rs.getLong("idle_s");
        double fastKm = rs.getDouble("fast_distance_m") / 1000.0;
        FuelEstimate fuel = FuelEstimator.estimate(distanceKm, idle / 3600.0, fastKm, a);
        return new VehicleRow(imei, rs.getString("vehicle_registration_no"), rs.getString("tenant_code"), rs.getInt("vehicles"),
                rs.getInt("days"), rs.getLong("readings"), instant(rs, "first_seen_at"), instant(rs, "last_seen_at"), distanceKm,
                moving, stopped, idle, rs.getInt("stops"), rs.getLong("stop_s"), rs.getInt("engine_on_stops"), rs.getInt("trips"),
                rs.getInt("max_speed_kmph"), moving > 0 ? distanceKm / (moving / 3600.0) : 0, fastKm,
                rs.getInt("economical_speed_kmph"), rs.getInt("overspeed_events"), rs.getInt("harsh_events"),
                moving + stopped > 0 ? 100.0 * moving / (moving + stopped) : 0, fuel);
    }

    /** The fleet line: sums, the highest speed, the oldest first and newest last reading. */
    private static VehicleRow totals(List<VehicleRow> rows, FuelAssumptions a) {
        double distance = 0, fast = 0;
        long readings = 0, moving = 0, stopped = 0, idle = 0, stopSeconds = 0;
        int stops = 0, engineOn = 0, trips = 0, maxSpeed = 0, overspeed = 0, harsh = 0, days = 0, economical = a.economicalSpeedKmph();
        Instant first = null, last = null;
        FuelEstimate fuel = FuelEstimate.NONE;
        for (VehicleRow r : rows) {
            distance += r.distanceKm();
            fast += r.fastKm();
            readings += r.readings();
            moving += r.movingSeconds();
            stopped += r.stoppedSeconds();
            idle += r.idleSeconds();
            stopSeconds += r.stopSeconds();
            stops += r.stops();
            engineOn += r.engineOnStops();
            trips += r.trips();
            maxSpeed = Math.max(maxSpeed, r.maxSpeedKmph());
            overspeed += r.overspeedEvents();
            harsh += r.harshEvents();
            days = Math.max(days, r.days());
            economical = r.economicalSpeedKmph() > 0 ? r.economicalSpeedKmph() : economical;
            first = first == null || (r.firstSeen() != null && r.firstSeen().isBefore(first)) ? r.firstSeen() : first;
            last = last == null || (r.lastSeen() != null && r.lastSeen().isAfter(last)) ? r.lastSeen() : last;
            fuel = fuel.plus(r.fuel());
        }
        return new VehicleRow(null, null, null, rows.size(), days, readings, first, last, distance, moving, stopped, idle, stops,
                stopSeconds, engineOn, trips, maxSpeed, moving > 0 ? distance / (moving / 3600.0) : 0, fast, economical, overspeed,
                harsh, moving + stopped > 0 ? 100.0 * moving / (moving + stopped) : 0, fuel);
    }

    /** ON when a STOPPAGE episode (engine running) overlaps the stop, OFF for a PARKED one, otherwise UNKNOWN. */
    private static String engineDuring(List<EventEntry> events, Instant start, Instant end) {
        boolean on = false, off = false;
        for (EventEntry e : events) {
            if (overlaps(e, start, end)) {
                on |= "STOPPAGE".equals(e.type()) || "IDLING".equals(e.type());
                off |= "PARKED".equals(e.type());
            }
        }
        return on ? "ON" : off ? "OFF" : "UNKNOWN";
    }

    private static long idleDuring(List<EventEntry> events, Instant start, Instant end) {
        long seconds = 0;
        for (EventEntry e : events) {
            if ("IDLING".equals(e.type()) && overlaps(e, start, end)) {
                Instant from = e.startedAt().isAfter(start) ? e.startedAt() : start;
                Instant to = e.endedAt() == null || e.endedAt().isAfter(end) ? end : e.endedAt();
                seconds += Math.max(0, to.getEpochSecond() - from.getEpochSecond());
            }
        }
        return seconds;
    }

    private static boolean overlaps(EventEntry e, Instant start, Instant end) {
        Instant eventEnd = e.endedAt() == null ? Instant.now() : e.endedAt();
        return e.startedAt().isBefore(end) && eventEnd.isAfter(start);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
