package com.srm.gps.application.geofence;

import com.srm.gps.application.geofence.GeofenceVisitStore.Filter;
import com.srm.gps.application.geofence.GeofenceVisitStore.Visit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The day-wise geofence report: where the day's time went, for the GPS provider, a transporter and a truck owner.
 *
 *   headline   visits, trucks, hours inside fences, median stay, over-long stays, still inside, uncertain stamps
 *   fences     per fence: visits, trucks, hours inside on the day, median / 90th percentile / longest stay, against the
 *              fence's own usual stay (median of the 30 days before) - "3.1x usual" is the queue or the detention
 *   occupancy  the busiest fences hour by hour: how many trucks were inside on average (when the gate is crowded)
 *   vehicles   per truck: a timeline of its visits across the 24 hours, hours inside (nested fences counted once),
 *              hours inside per role (plant, customer, yard), and its driving time from the day summary
 *   insights   plain sentences, worst first: the stays far above usual, fences slower than usual, trucks still held,
 *              where most of the time went, and which stamps are uncertain because of GPS gaps
 *
 * A visit crossing midnight counts on each day for the minutes it spent in that day; its length (median, longest) is the
 * whole stay. Open visits run until now. Medians, not means: stays are right-skewed and a few multi-day holds would set
 * the "typical" figure (the same choice as smart_truck's detention report).
 */
@Service
public class GeofenceReportService {

    /** A stamp further than this from the reading next to it is shown as uncertain (smart_truck MAX_TRUSTED_GAP_MIN). */
    static final int UNCERTAIN_AFTER_MINUTES = 60;
    private static final int MAX_VISITS = 50_000;
    private static final int HISTORY_DAYS = 30;
    private static final int OCCUPANCY_FENCES = 8;

    private final GeofenceVisitStore visits;
    private final GeofenceDirectory directory;
    private final GeofenceModule module;
    private final NamedParameterJdbcTemplate jdbc;
    private final ZoneId zone;

    public GeofenceReportService(GeofenceVisitStore visits, GeofenceDirectory directory, GeofenceModule module,
                                 NamedParameterJdbcTemplate jdbc, @Value("${gps.reports.zone:Asia/Kolkata}") String zone) {
        this.visits = visits;
        this.directory = directory;
        this.module = module;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** The visits and passes overlapping the day, as stored (the CSV export). */
    public List<Visit> dayVisits(LocalDate date, Filter filter) {
        ZonedDateTime start = date.atStartOfDay(zone);
        return visits.overlapping(start.toInstant(), start.plusDays(1).toInstant(), filter, MAX_VISITS);
    }

    public Map<String, Object> day(LocalDate date, Filter filter) {
        ZonedDateTime startOfDay = date.atStartOfDay(zone);
        Instant from = startOfDay.toInstant();
        Instant to = startOfDay.plusDays(1).toInstant();
        Instant now = Instant.now();
        Instant until = now.isBefore(to) ? now : to;

        List<Visit> all = from.isAfter(now) ? List.of() : visits.overlapping(from, to, filter, MAX_VISITS);
        List<Visit> stays = all.stream().filter(v -> "VISIT".equals(v.kind())).toList();
        List<Visit> passes = all.stream().filter(v -> "PASS".equals(v.kind())).toList();
        Map<Long, double[]> usual = visits.typicalDwell(from.minus(Duration.ofDays(HISTORY_DAYS)), from);

        List<Map<String, Object>> fenceRows = fences(stays, passes, usual, from, until);
        List<Map<String, Object>> vehicleRows = vehicles(date, stays, passes, from, until);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", date.toString());
        report.put("zone", zone.getId());
        report.put("from", from);
        report.put("to", to);
        report.put("partial", now.isBefore(to));
        report.put("generatedAt", now);
        report.put("headline", headline(stays, passes, from, until));
        report.put("fences", fenceRows);
        report.put("occupancy", occupancy(stays, fenceRows, from, until));
        report.put("vehicles", vehicleRows);
        report.put("insights", insights(stays, fenceRows, usual, until));
        report.put("method", method());
        return report;
    }

    // ------------------------------------------------------------------------------------------------ sections

    private Map<String, Object> headline(List<Visit> stays, List<Visit> passes, Instant from, Instant until) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("visits", stays.size());
        h.put("passes", passes.size());
        h.put("vehicles", stays.stream().map(Visit::imei).distinct().count());
        h.put("fencesVisited", stays.stream().map(Visit::placeId).distinct().count());
        h.put("minutesInside", stays.stream().mapToLong(v -> minutesWithin(v, from, until)).sum());
        h.put("medianStayMinutes", percentile(stays.stream().map(v -> (double) v.minutes()).toList(), 0.5));
        h.put("longStays", stays.stream().filter(v -> v.dwellAlertAt() != null).count());
        h.put("stillInside", stays.stream().filter(v -> v.open() && !v.noSignal()).count());
        h.put("insideWhenLastSeen", stays.stream().filter(Visit::noSignal).count());
        h.put("uncertainStamps", stays.stream().filter(v -> v.uncertaintyMinutes() > UNCERTAIN_AFTER_MINUTES).count());
        return h;
    }

    private List<Map<String, Object>> fences(List<Visit> stays, List<Visit> passes, Map<Long, double[]> usual, Instant from, Instant until) {
        Map<Long, List<Visit>> byFence = stays.stream().collect(Collectors.groupingBy(Visit::placeId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Long> passCount = passes.stream().collect(Collectors.groupingBy(Visit::placeId, Collectors.counting()));
        Set<Long> ids = new HashSet<>(byFence.keySet());
        ids.addAll(passCount.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id : ids) {
            List<Visit> vs = byFence.getOrDefault(id, List.of());
            Visit any = vs.isEmpty() ? passes.stream().filter(p -> p.placeId() == id).findFirst().orElseThrow() : vs.getFirst();
            List<Double> lengths = vs.stream().map(v -> (double) v.minutes()).toList();
            Visit longest = vs.stream().max(Comparator.comparingLong(Visit::minutes)).orElse(null);
            double[] typical = usual.get(id);
            Double median = percentile(lengths, 0.5);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("fenceId", id);
            f.put("code", any.placeCode());
            f.put("name", any.placeName());
            f.put("role", any.placeRole());
            f.put("visits", vs.size());
            f.put("vehicles", vs.stream().map(Visit::imei).distinct().count());
            f.put("minutesInside", vs.stream().mapToLong(v -> minutesWithin(v, from, until)).sum());
            f.put("medianStayMinutes", median);
            f.put("p90StayMinutes", percentile(lengths, 0.9));
            f.put("longestStayMinutes", longest == null ? null : longest.minutes());
            f.put("longestVehicle", longest == null ? null : label(longest));
            f.put("stillInside", vs.stream().filter(v -> v.open() && !v.noSignal()).count());
            f.put("insideWhenLastSeen", vs.stream().filter(Visit::noSignal).count());
            f.put("longStays", vs.stream().filter(v -> v.dwellAlertAt() != null).count());
            f.put("passes", passCount.getOrDefault(id, 0L));
            f.put("dwellLimitMinutes", directory.index().byId(id).map(module.settings()::dwellAlertMinutes).orElse(null));
            f.put("usualVisits", typical == null ? 0 : (long) typical[0]);
            f.put("usualMedianMinutes", typical == null ? null : round(typical[1]));
            f.put("usualP90Minutes", typical == null ? null : round(typical[2]));
            f.put("vsUsual", typical == null || median == null || typical[1] < 1 ? null : round2(median / typical[1]));
            rows.add(f);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> r) -> (long) r.get("minutesInside")).reversed()
                .thenComparing(r -> -((Number) r.get("passes")).longValue()));
        return rows;
    }

    /** Average trucks inside per hour, for the busiest fences. */
    private List<Map<String, Object>> occupancy(List<Visit> stays, List<Map<String, Object>> fenceRows, Instant from, Instant until) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> fence : fenceRows.stream().filter(r -> (long) r.get("minutesInside") > 0).limit(OCCUPANCY_FENCES).toList()) {
            long id = (long) fence.get("fenceId");
            double[] hours = new double[24];
            for (Visit v : stays) {
                if (v.placeId() != id) {
                    continue;
                }
                for (int h = 0; h < 24; h++) {
                    Instant hourStart = from.plus(Duration.ofHours(h));
                    Instant hourEnd = hourStart.plus(Duration.ofHours(1));
                    if (!hourStart.isBefore(until)) {
                        break;
                    }
                    hours[h] += overlapMinutes(v, hourStart, hourEnd.isAfter(until) ? until : hourEnd) / 60.0;
                }
            }
            List<Double> averages = new ArrayList<>();
            for (double h : hours) {
                averages.add(round2(h));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fenceId", id);
            row.put("name", fence.get("name"));
            row.put("role", fence.get("role"));
            row.put("averageInsideByHour", averages);
            row.put("peakHour", indexOfMax(hours));
            row.put("peakAverageInside", round2(hours[indexOfMax(hours)]));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> vehicles(LocalDate date, List<Visit> stays, List<Visit> passes, Instant from, Instant until) {
        Map<String, List<Visit>> byVehicle = stays.stream().collect(Collectors.groupingBy(Visit::imei, LinkedHashMap::new, Collectors.toList()));
        Map<String, Long> passCount = passes.stream().collect(Collectors.groupingBy(Visit::imei, Collectors.counting()));
        Map<String, Map<String, Object>> summaries = daySummaries(date, byVehicle.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Visit>> e : byVehicle.entrySet()) {
            List<Visit> vs = e.getValue();
            Map<String, Long> byRole = new LinkedHashMap<>();
            List<Map<String, Object>> timeline = new ArrayList<>();
            // per role, nested fences of one role (two customer fences one inside the other) counted once
            vs.stream().collect(Collectors.groupingBy(Visit::placeRole, LinkedHashMap::new, Collectors.toList()))
                    .forEach((role, ofRole) -> byRole.put(role, unionMinutes(ofRole, from, until)));
            for (Visit v : vs) {
                Instant segmentStart = v.enteredAt().isBefore(from) ? from : v.enteredAt();
                Instant end = v.until();
                Instant segmentEnd = end.isAfter(until) ? until : end;
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("fenceId", v.placeId());
                t.put("name", v.placeName());
                t.put("role", v.placeRole());
                t.put("enteredAt", v.enteredAt());
                t.put("exitedAt", v.exitedAt());
                t.put("startMinute", Math.max(0, Duration.between(from, segmentStart).toMinutes()));
                t.put("endMinute", Math.max(0, Duration.between(from, segmentEnd).toMinutes()));
                t.put("stayMinutes", v.minutes());
                t.put("open", v.open());
                t.put("noSignalMinutes", v.noSignal() ? v.silentMinutes() : 0);
                t.put("overLimit", v.dwellAlertAt() != null);
                t.put("startedBefore", v.enteredAt().isBefore(from));
                t.put("uncertainMinutes", v.uncertaintyMinutes() > UNCERTAIN_AFTER_MINUTES ? v.uncertaintyMinutes() : 0);
                timeline.add(t);
            }
            Visit first = vs.getFirst();
            Visit longest = vs.stream().max(Comparator.comparingLong(Visit::minutes)).orElseThrow();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("imei", e.getKey());
            row.put("vehicleRegistrationNo", first.vehicleRegistrationNo());
            row.put("tenantCode", first.tenantCode());
            row.put("visits", vs.size());
            row.put("passes", passCount.getOrDefault(e.getKey(), 0L));
            row.put("minutesInside", unionMinutes(vs, from, until));
            row.put("minutesByRole", byRole);
            row.put("longestStay", Map.of("name", longest.placeName(), "minutes", longest.minutes()));
            row.put("stillInside", vs.stream().filter(v -> v.open() && !v.noSignal()).map(Visit::placeName).toList());
            row.put("silentMinutes", vs.stream().filter(Visit::noSignal).mapToLong(Visit::silentMinutes).max().orElse(0));
            row.put("day", summaries.get(e.getKey()));
            row.put("timeline", timeline);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Map<String, Object> r) -> (long) r.get("minutesInside")).reversed());
        return rows;
    }

    private List<Map<String, Object>> insights(List<Visit> stays, List<Map<String, Object>> fenceRows, Map<Long, double[]> usual, Instant until) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stays.isEmpty()) {
            out.add(insight("INFO", "No geofence visits on this day for the vehicles in view.", null, null));
            return out;
        }
        // 1. stays far above what is usual at that fence (or over its limit when there is no history yet); one per truck,
        //    the most unusual of its stays - nested fences (a gate yard inside a plant) would otherwise say it twice
        Map<String, Visit> unusualByTruck = new LinkedHashMap<>();
        for (Visit v : stays) {
            double[] typical = usual.get(v.placeId());
            boolean far = typical != null && typical[0] >= 5 && v.minutes() >= 60 && v.minutes() > 2 * typical[1];
            if (far || (typical == null && v.dwellAlertAt() != null)) {
                unusualByTruck.merge(v.imei(), v, (a, b) -> ratio(b, usual) > ratio(a, usual) ? b : a);
            }
        }
        List<Visit> unusual = new ArrayList<>(unusualByTruck.values());
        unusual.sort(Comparator.comparingLong(Visit::minutes).reversed());
        for (Visit v : unusual.stream().limit(5).toList()) {
            double[] typical = usual.get(v.placeId());
            String where = v.noSignal() ? " (inside when its GPS was last heard, " + duration(v.silentMinutes()) + " ago)"
                    : v.open() ? " and is still there" : "";
            String text = typical != null && typical[0] >= 5
                    ? String.format("%s spent %s at %s%s: %.1fx the usual %s (median of %d visits in the last %d days).",
                    label(v), duration(v.minutes()), v.placeName(), where, v.minutes() / typical[1],
                    duration(Math.round(typical[1])), (long) typical[0], HISTORY_DAYS)
                    : String.format("%s spent %s at %s%s, over the fence's %s limit.", label(v), duration(v.minutes()), v.placeName(),
                    where, duration(v.dwellLimitMinutes() == null ? 0 : v.dwellLimitMinutes()));
            boolean severe = typical != null && v.minutes() > typical[2];
            out.add(insight(severe ? "HIGH" : "MEDIUM", text, v.placeId(), v.imei()));
        }
        if (unusualByTruck.size() > 5) {
            out.add(insight("MEDIUM", String.format("%d more trucks stayed more than twice as long as usual somewhere; see each truck's day below.",
                    unusualByTruck.size() - 5), null, null));
        }
        // 2. fences slower than usual across several visits: a queue, a slow dock, a detention habit
        for (Map<String, Object> f : fenceRows) {
            Object ratio = f.get("vsUsual");
            if (ratio instanceof Double r && r >= 1.5 && (int) f.get("visits") >= 3) {
                out.add(insight("MEDIUM", String.format("Stays at %s took %s (median of %d visits) against a usual %s: %.1fx slower than normal.",
                        f.get("name"), duration(((Number) f.get("medianStayMinutes")).longValue()), (int) f.get("visits"),
                        duration(((Number) f.get("usualMedianMinutes")).longValue()), r), (Long) f.get("fenceId"), null));
            }
        }
        // 3. trucks held right now past the fence's limit (reporting now: a silent tracker is not proof it is still there)
        Map<String, List<Visit>> heldByFence = stays.stream().filter(v -> v.open() && !v.noSignal() && v.dwellAlertAt() != null)
                .collect(Collectors.groupingBy(Visit::placeName, LinkedHashMap::new, Collectors.toList()));
        heldByFence.forEach((name, held) -> out.add(insight("HIGH", String.format("%d %s been inside %s longer than its %s limit and %s still there: %s.",
                held.size(), held.size() == 1 ? "truck has" : "trucks have", name,
                duration(held.getFirst().dwellLimitMinutes() == null ? 0 : held.getFirst().dwellLimitMinutes()),
                held.size() == 1 ? "is" : "are", held.stream().map(GeofenceReportService::label).limit(6).collect(Collectors.joining(", "))),
                held.getFirst().placeId(), null)));
        // 4. where the time went
        long total = fenceRows.stream().mapToLong(r -> (long) r.get("minutesInside")).sum();
        if (total > 0) {
            Map<String, Object> top = fenceRows.getFirst();
            long minutes = (long) top.get("minutesInside");
            out.add(insight("INFO", String.format("Most time inside fences went to %s: %s across %d truck%s (%d%% of all %s inside fences).",
                    top.get("name"), duration(minutes), ((Number) top.get("vehicles")).longValue(),
                    ((Number) top.get("vehicles")).longValue() == 1 ? "" : "s", Math.round(100.0 * minutes / total), duration(total)),
                    (Long) top.get("fenceId"), null));
        }
        // 5. honesty about the stamps
        long uncertain = stays.stream().filter(v -> v.uncertaintyMinutes() > UNCERTAIN_AFTER_MINUTES).count();
        if (uncertain > 0) {
            out.add(insight("INFO", String.format("%d visit time%s %s uncertain by more than an hour because the GPS was silent around the crossing; they are marked ±.",
                    uncertain, uncertain == 1 ? "" : "s", uncertain == 1 ? "is" : "are"), null, null));
        }
        out.sort(Comparator.comparingInt(i -> rank((String) i.get("severity"))));
        return out;
    }

    private Map<String, Object> method() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visit", "Inside a fence for " + module.settings().confirmSeconds() / 60 + " minutes or more (the smart_truck rule); shorter is a pass");
        m.put("stamps", "Entry = first reading inside, exit = last reading inside; never interpolated");
        m.put("dayShare", "A stay crossing midnight counts on each day for its minutes in that day; its length is the whole stay");
        m.put("usual", "Median and 90th percentile of the fence's closed visits in the " + HISTORY_DAYS + " days before");
        m.put("uncertain", "A stamp more than " + UNCERTAIN_AFTER_MINUTES + " minutes from the reading next to it (a GPS gap)");
        m.put("rules", module.settings());
        return m;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The day summary (vehicle_day_summary) of these devices: driving and standing time, distance, all generations added up. */
    private Map<String, Map<String, Object>> daySummaries(LocalDate date, Set<String> imeis) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (imeis.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT imei, sum(readings) AS readings, sum(distance_m) AS distance_m, sum(moving_s) AS moving_s,
                       sum(stopped_s) AS stopped_s, max(max_speed_kmph) AS max_speed, sum(overspeed_events) AS overspeed
                FROM vehicle_day_summary WHERE day = :day AND imei IN (:imeis) GROUP BY imei
                """, new MapSqlParameterSource("day", date).addValue("imeis", imeis), rs -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("readings", rs.getLong("readings"));
            s.put("distanceKm", round2(rs.getDouble("distance_m") / 1000));
            s.put("movingMinutes", rs.getLong("moving_s") / 60);
            s.put("stoppedMinutes", rs.getLong("stopped_s") / 60);
            s.put("maxSpeedKmph", rs.getInt("max_speed"));
            s.put("overspeedEvents", rs.getLong("overspeed"));
            out.put(rs.getString("imei"), s);
        });
        return out;
    }

    private static long minutesWithin(Visit v, Instant from, Instant until) {
        return overlapMinutes(v, from, until);
    }

    private static long overlapMinutes(Visit v, Instant from, Instant until) {
        Instant end = v.until();
        Instant a = v.enteredAt().isAfter(from) ? v.enteredAt() : from;
        Instant b = end.isBefore(until) ? end : until;
        return b.isAfter(a) ? Duration.between(a, b).toMinutes() : 0;
    }

    /** Minutes inside any fence: nested fences (a plant inside its town) are counted once. */
    private static long unionMinutes(List<Visit> vs, Instant from, Instant until) {
        List<Instant[]> spans = new ArrayList<>();
        for (Visit v : vs) {
            Instant end = v.until();
            Instant a = v.enteredAt().isAfter(from) ? v.enteredAt() : from;
            Instant b = end.isBefore(until) ? end : until;
            if (b.isAfter(a)) {
                spans.add(new Instant[]{a, b});
            }
        }
        spans.sort(Comparator.comparing(s -> s[0]));
        long minutes = 0;
        Instant curStart = null;
        Instant curEnd = null;
        for (Instant[] s : spans) {
            if (curEnd == null || s[0].isAfter(curEnd)) {
                if (curEnd != null) {
                    minutes += Duration.between(curStart, curEnd).toMinutes();
                }
                curStart = s[0];
                curEnd = s[1];
            } else if (s[1].isAfter(curEnd)) {
                curEnd = s[1];
            }
        }
        if (curEnd != null) {
            minutes += Duration.between(curStart, curEnd).toMinutes();
        }
        return minutes;
    }

    static Double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = values.stream().sorted().toList();
        return round(sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(sorted.size() * p))));
    }

    static String duration(long minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return days + " d " + hours + " h";
        }
        return mins == 0 ? hours + " h" : hours + " h " + mins + " min";
    }

    /** How many times its fence's usual stay a visit lasted (0 without history). */
    private static double ratio(Visit v, Map<Long, double[]> usual) {
        double[] typical = usual.get(v.placeId());
        return typical == null || typical[1] < 1 ? 0 : v.minutes() / typical[1];
    }

    private static String label(Visit v) {
        return v.vehicleRegistrationNo() != null ? v.vehicleRegistrationNo() : "Device " + v.imei();
    }

    private static Map<String, Object> insight(String severity, String text, Long fenceId, String imei) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("text", text);
        m.put("fenceId", fenceId);
        m.put("imei", imei);
        return m;
    }

    private static int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private static int indexOfMax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private static Double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
