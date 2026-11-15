package com.srm.gps.application.road;

import com.srm.gps.connectivity.smarttruck.SmartTruckGpsPing;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills the road library with Valhalla's map matching, two ways:
 *
 *   learn a trip   every ping of a smart_truck trip (read-only), on request: the roads a lane uses are known before a
 *                  truck drives it, so the live check has the road limit from the first reading
 *   live           every few minutes, each device's new readings since its cursor (road_match_cursor), once it has
 *                  minLivePoints of them or the oldest is maxLiveWaitMinutes old: roads nobody learned yet
 *
 * Both run on one background thread, never on the Kafka thread; when Valhalla does not answer the run stops and the
 * cursors stay, so nothing is skipped.
 */
@Service
public class RoadLearningService {

    private static final Logger LOG = LoggerFactory.getLogger(RoadLearningService.class);

    /** The newest learning run, for the console. */
    public record Status(boolean running, String what, Instant startedAt, Instant finishedAt, int pointsSent, int pieces,
                         int failedPieces, int roadsMatched, int roadsAdded, int unmatchedPoints, long valhallaMillis,
                         String message) {
    }

    private final RoadMatcher matcher;
    private final RoadLibrary library;
    private final SmartTruckGpsReader smartTruck;
    private final NamedParameterJdbcTemplate jdbc;
    private final RoadProperties properties;
    private final RoadPassService passes;
    private final RoadRecords records;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "road-learning");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Status lastRun = new Status(false, "nothing yet", null, null, 0, 0, 0, 0, 0, 0, 0, "No learning run yet");
    private volatile Status lastLiveRun;

    public RoadLearningService(RoadMatcher matcher, RoadLibrary library, SmartTruckGpsReader smartTruck,
                               NamedParameterJdbcTemplate jdbc, RoadProperties properties, RoadPassService passes,
                               RoadRecords records) {
        this.matcher = matcher;
        this.library = library;
        this.smartTruck = smartTruck;
        this.jdbc = jdbc;
        this.properties = properties;
        this.passes = passes;
        this.records = records;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("valhallaUrl", matcher.valhallaUrl());
        status.put("liveMatching", properties.liveMatchingOrDefault());
        status.put("busy", busy.get());
        status.put("lastRun", lastRun);
        status.put("lastLiveRun", lastLiveRun);
        try {
            RoadLibrary.Snapshot s = library.snapshot();
            status.put("roads", s.roads());
            status.put("kilometres", Math.round(s.kilometres()));
        } catch (RuntimeException databaseDown) {
            status.put("roads", null);
        }
        return status;
    }

    /** Learns the roads of one smart_truck trip in the background. False when a run is already going. */
    public boolean learnSmartTruckTrip(long tripNumber) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        Instant started = Instant.now();
        lastRun = new Status(true, "smart_truck trip " + tripNumber, started, null, 0, 0, 0, 0, 0, 0, 0, "Reading the trip's pings");
        worker.execute(() -> {
            try {
                List<SmartTruckGpsPing> pings = smartTruck.readPingsOfTrip(tripNumber, 1_000_000);
                List<double[]> points = new ArrayList<>(pings.size());
                for (SmartTruckGpsPing p : pings) {
                    points.add(new double[]{p.latitude(), p.longitude()});
                }
                lastRun = new Status(true, "smart_truck trip " + tripNumber, started, null, points.size(), 0, 0, 0, 0, 0, 0,
                        "Map matching " + points.size() + " pings on Valhalla");
                lastRun = learn("smart_truck trip " + tripNumber, tripNumber, started, points);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception problem) {
                LOG.warn("Learning the roads of trip {} failed: {}", tripNumber, problem.getMessage());
                records.runFinished(new RoadRecords.Run("TRIP", null, tripNumber, null, null, started, null, 0,
                        problem instanceof RoadMatcher.ValhallaUnavailable ? "DOWN" : "FAILED", problem.getMessage()));
                lastRun = new Status(false, "smart_truck trip " + tripNumber, started, Instant.now(), 0, 0, 0, 0, 0, 0, 0,
                        "Failed: " + problem.getMessage());
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    private Status learn(String what, long tripNumber, Instant started, List<double[]> points)
            throws RoadMatcher.ValhallaUnavailable, InterruptedException {
        RoadMatcher.Result result = matcher.match(points);
        int added = library.store(result.roads(), result.details());
        library.reload();
        records.storePoints(result.points().values());
        String message = "Matched " + result.roads().size() + " road pieces (" + added + " new) in " + result.pieces() + " calls"
                + (result.failedPieces() > 0 ? ", " + result.failedPieces() + " pieces not matchable: " + result.problem() : "");
        LOG.info("Roads of {}: {}", what, message);
        records.runFinished(new RoadRecords.Run("TRIP", null, tripNumber, null, null, started, result, added,
                result.failedPieces() > 0 ? "PARTLY" : "OK", message));
        return new Status(false, what, started, Instant.now(), result.pointsSent(), result.pieces(), result.failedPieces(),
                result.roads().size(), added, result.unmatchedPoints(), result.millis(), message);
    }

    /** Live matching: every device with new readings, one after the other, on the learning thread. */
    @Scheduled(fixedDelayString = "${gps.roads.match-every:2m}", initialDelayString = "${gps.roads.first-match-after:60s}")
    public void matchLiveReadings() {
        if (!properties.liveMatchingOrDefault() || !busy.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            Instant started = Instant.now();
            int devices = 0;
            int points = 0;
            int matched = 0;
            int added = 0;
            String message;
            try {
                for (Map<String, Object> device : devicesWithNewReadings()) {
                    String imei = (String) device.get("imei");
                    Instant from = ((Timestamp) device.get("matched_until")).toInstant();
                    List<Object[]> readings = readingsAfter(imei, from);
                    if (readings.size() < 2) {
                        continue;
                    }
                    Instant oldest = (Instant) readings.getFirst()[2];
                    boolean enough = readings.size() >= properties.minLivePointsOrDefault()
                            || Duration.between(oldest, Instant.now()).toMinutes() >= properties.maxLiveWaitMinutesOrDefault();
                    if (!enough) {
                        continue;
                    }
                    List<double[]> line = new ArrayList<>();
                    for (Object[] r : readings) {
                        line.add(new double[]{(double) r[0], (double) r[1]});
                    }
                    Instant deviceStarted = Instant.now();
                    Instant last = (Instant) readings.getLast()[2];
                    RoadMatcher.Result result;
                    try {
                        result = matcher.match(line);
                    } catch (RoadMatcher.ValhallaUnavailable down) {
                        records.runFinished(new RoadRecords.Run("LIVE", imei, null, from, last, deviceStarted, null, 0, "DOWN",
                                down.getMessage()));
                        throw down;
                    }
                    int newHere = library.store(result.roads(), result.details());
                    added += newHere;
                    if (newHere > 0) {
                        library.reload();
                    }
                    records.storePoints(result.points().values());
                    matched += result.roads().size();
                    keepForAnalysis(imei, from, last);
                    records.runFinished(new RoadRecords.Run("LIVE", imei, null, from, last, deviceStarted, result, newHere,
                            result.failedPieces() > 0 ? "PARTLY" : "OK", result.failedPieces() > 0 ? result.problem() : null));
                    points += result.pointsSent();
                    devices++;
                    // the last reading stays as the start of the next piece, so the line has no hole between runs
                    Instant until = (Instant) readings.get(readings.size() - 2)[2];
                    jdbc.update("""
                            INSERT INTO road_match_cursor (imei, matched_until) VALUES (:imei, :until)
                            ON CONFLICT (imei) DO UPDATE SET matched_until = EXCLUDED.matched_until, updated_at = now()
                            """, new MapSqlParameterSource("imei", imei).addValue("until", Timestamp.from(until)));
                }
                if (added > 0) {
                    library.reload();
                }
                message = devices == 0 ? "No device had enough new readings" : "Matched " + points + " points of " + devices
                        + " devices: " + matched + " road pieces, " + added + " new";
            } catch (RoadMatcher.ValhallaUnavailable down) {
                message = "Stopped: " + down.getMessage();
                LOG.debug("Live road matching: {}", message);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                message = "Interrupted";
            } catch (RuntimeException problem) {
                message = "Failed: " + problem.getMessage();
                LOG.warn("Live road matching failed: {}", problem.getMessage());
            } finally {
                busy.set(false);
            }
            lastLiveRun = new Status(false, "live readings", started, Instant.now(), points, 0, 0, matched, added, 0, 0, message);
        });
    }

    /** The road under each matched reading and the device's road passes (V13); a failure here never stops the matching. */
    private void keepForAnalysis(String imei, Instant from, Instant until) {
        try {
            passes.assignRoads(imei, from, until);
            passes.rebuildPasses(imei, from, until);
            records.linkEvents(imei, from, until);
        } catch (RuntimeException problem) {
            LOG.warn("Road passes of {} not updated: {}", imei, problem.getMessage());
        }
    }

    /** Devices with readings newer than their cursor (a new device starts a day back). */
    private List<Map<String, Object>> devicesWithNewReadings() {
        return jdbc.queryForList("""
                SELECT p.imei, coalesce(c.matched_until, min(p.recorded_at) - interval '1 second') AS matched_until
                FROM gps_position p LEFT JOIN road_match_cursor c ON c.imei = p.imei
                WHERE p.stored_at > now() - interval '1 day'
                GROUP BY p.imei, c.matched_until
                HAVING max(p.recorded_at) > coalesce(c.matched_until, '-infinity'::timestamptz)
                """, new MapSqlParameterSource());
    }

    /** [latitude, longitude, recordedAt] of up to 2,000 readings after the cursor, oldest first. */
    private List<Object[]> readingsAfter(String imei, Instant from) {
        return jdbc.query("""
                SELECT latitude, longitude, recorded_at FROM gps_position
                WHERE imei = :imei AND recorded_at > :from ORDER BY recorded_at LIMIT 2000
                """, new MapSqlParameterSource("imei", imei).addValue("from", Timestamp.from(from)),
                (rs, n) -> new Object[]{rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getTimestamp("recorded_at").toInstant()});
    }

    /** After "Clear database": the cursors point at readings that are gone. */
    public void forgetCursors() {
        jdbc.update("DELETE FROM road_match_cursor", new MapSqlParameterSource());
    }
}
