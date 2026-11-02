package com.srm.gps.processing.geofence;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Replays smart_truck trips (tta_trip_gps) through the live GeofenceTracker and compares its enter/exit events with
 * smart_truck's own batch results (tta_trip_geofence_events). Read-only. Not a unit test (it needs the smart_truck
 * MySQL); run it by hand, see docs/GEOFENCING_GUIDE.md:
 *
 *   ST_USER=... ST_PASS=... java -cp gps-processing/target/classes;gps-processing/target/test-classes;mysql-connector-j.jar
 *       com.srm.gps.processing.geofence.SmartTruckParityCheck 2220
 *
 * Args: number of trips (the newest with events). 2026-11-02, all 2,220 trips: 95.6 % of smart_truck's events found
 * again, 98.8 % of ours confirmed, 0.51 microseconds per reading.
 */
public class SmartTruckParityCheck {

    record Ev(long trip, long fence, String kind, LocalDateTime at) {}

    public static void main(String[] args) throws Exception {
        int tripLimit = args.length > 0 ? Integer.parseInt(args[0]) : 300;
        ZoneOffset ist = ZoneOffset.ofHoursMinutes(5, 30);
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/smart_truck?useCursorFetch=true",
                System.getenv("ST_USER"), System.getenv("ST_PASS"))) {
            c.setReadOnly(true);
            List<Geofence> fences = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT i_fence_id, s_key, s_name, UPPER(s_role) r, d_lat, d_long, i_radius_m FROM geofences WHERE b_active = 1")) {
                while (rs.next()) {
                    fences.add(Geofence.circle(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getDouble(5), rs.getDouble(6), rs.getInt(7), null, null));
                }
            }
            GeofenceIndex index = GeofenceIndex.of(fences);
            GeofenceSettings settings = GeofenceSettings.defaults();
            System.out.println("fences " + fences.size() + " index " + index.stats());

            List<Long> trips = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT DISTINCT i_trip_no FROM tta_trip_geofence_events ORDER BY i_trip_no DESC LIMIT " + tripLimit)) {
                while (rs.next()) trips.add(rs.getLong(1));
            }
            String in = String.join(",", trips.stream().map(String::valueOf).toList());

            List<Ev> theirs = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT i_trip_no, i_fence_id, s_event, dt_event FROM tta_trip_geofence_events WHERE i_trip_no IN (" + in + ")")) {
                while (rs.next()) theirs.add(new Ev(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getTimestamp(4).toLocalDateTime()));
            }

            List<Ev> ours = new ArrayList<>();
            Map<Long, LocalDateTime> firstPing = new HashMap<>();
            long pings = 0;
            long nanos = 0;
            long passes = 0;
            long dwellAlerts = 0;
            PreparedStatement ps = c.prepareStatement("SELECT dt_message, d_lat, d_long, i_speed FROM tta_trip_gps WHERE i_trip_no = ? ORDER BY dt_message");
            ps.setFetchSize(5000);
            for (long trip : trips) {
                ps.setLong(1, trip);
                GeofenceTrackState state = GeofenceTrackState.EMPTY;
                Instant previous = null;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDateTime local = rs.getTimestamp(1).toLocalDateTime();
                        Instant at = local.toInstant(ist);
                        if (previous != null && !at.isAfter(previous)) continue;
                        if (previous == null) firstPing.put(trip, local);
                        long since = previous == null ? 0 : Duration.between(previous, at).getSeconds();
                        GeofenceReading r = new GeofenceReading(at, rs.getDouble(2), rs.getDouble(3), rs.getInt(4), since, false);
                        long t0 = System.nanoTime();
                        GeofenceTracker.Update u = GeofenceTracker.update(state, r, index, settings);
                        nanos += System.nanoTime() - t0;
                        pings++;
                        state = u.state();
                        previous = at;
                        for (GeofenceFinding f : u.findings()) {
                            switch (f.kind()) {
                                case ENTERED -> ours.add(new Ev(trip, f.fence().id(), "enter", LocalDateTime.ofInstant(f.at(), ist)));
                                case EXITED -> ours.add(new Ev(trip, f.fence().id(), "exit", LocalDateTime.ofInstant(f.at(), ist)));
                                case PASSED -> passes++;
                                case DWELL_EXCEEDED -> dwellAlerts++;
                            }
                        }
                    }
                }
            }
            // smart_truck starts a trip in the state of its first ping (no "enter" for a trip that starts inside); we start outside
            List<Ev> oursComparable = ours.stream()
                    .filter(e -> !(e.kind().equals("enter") && e.at().equals(firstPing.get(e.trip())))).toList();

            Map<String, List<Ev>> theirsByKey = new HashMap<>();
            for (Ev e : theirs) theirsByKey.computeIfAbsent(e.trip() + "|" + e.fence() + "|" + e.kind(), k -> new ArrayList<>()).add(e);
            int matched = 0;
            int exact = 0;
            List<Long> diffs = new ArrayList<>();
            Set<Ev> used = new HashSet<>();
            for (Ev e : oursComparable) {
                List<Ev> candidates = theirsByKey.getOrDefault(e.trip() + "|" + e.fence() + "|" + e.kind(), List.of());
                Ev best = null;
                long bestDiff = Long.MAX_VALUE;
                for (Ev t : candidates) {
                    if (used.contains(t)) continue;
                    long d = Math.abs(Duration.between(t.at(), e.at()).getSeconds());
                    if (d < bestDiff) { bestDiff = d; best = t; }
                }
                if (best != null && bestDiff <= 15 * 60) {
                    used.add(best);
                    matched++;
                    if (bestDiff == 0) exact++;
                    diffs.add(bestDiff);
                }
            }
            Collections.sort(diffs);
            System.out.printf("trips %d, pings %,d, tracker %.2f us/ping (%,.0f pings/s on one thread)%n",
                    trips.size(), pings, nanos / 1000.0 / pings, pings / (nanos / 1e9));
            System.out.printf("smart_truck events %d | ours %d (comparable %d) | matched %d (%.1f%% of theirs, %.1f%% of ours), exact time %d%n",
                    theirs.size(), ours.size(), oursComparable.size(), matched, 100.0 * matched / theirs.size(),
                    100.0 * matched / oursComparable.size(), exact);
            if (!diffs.isEmpty()) {
                System.out.printf("time difference of matches: median %ds, p90 %ds, max %ds%n",
                        diffs.get(diffs.size() / 2), diffs.get((int) (diffs.size() * 0.9)), diffs.getLast());
            }
            System.out.printf("extra: %d passes, %d dwell alerts%n", passes, dwellAlerts);
            // a few unmatched of each side, to see why
            System.out.println("-- smart_truck only (first 8):");
            theirs.stream().filter(t -> !used.contains(t)).limit(8).forEach(System.out::println);
            System.out.println("-- ours only (first 8):");
            Set<String> matchedKeys = new HashSet<>();
            used.forEach(t -> matchedKeys.add(t.trip() + "|" + t.fence() + "|" + t.kind() + "|" + t.at()));
            oursComparable.stream().filter(e -> theirsByKey.getOrDefault(e.trip() + "|" + e.fence() + "|" + e.kind(), List.of()).stream()
                    .noneMatch(t -> Math.abs(Duration.between(t.at(), e.at()).getSeconds()) <= 900)).limit(8).forEach(System.out::println);
        }
    }
}
