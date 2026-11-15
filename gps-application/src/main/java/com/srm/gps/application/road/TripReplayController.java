package com.srm.gps.application.road;

import com.srm.gps.application.admin.DemoResetService;
import com.srm.gps.application.simulator.GpsDeviceSimulatorService;
import com.srm.gps.application.simulator.GpsDeviceSimulatorSettings;
import com.srm.gps.application.simulator.SimulationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one-trip test bed of Road Intelligence (trip 28860462, Jamshedpur → Ghaziabad):
 *
 *   POST /api/roads/replay        delete the run and replay the trip from its first ping: the demo reset (Kafka, the
 *                                 telemetry tables, Redis, reports) and, with forgetRoads, the learned road pieces too
 *                                 (pieces with a hand-set limit are kept), so the console shows Valhalla matching the
 *                                 roads and the limits appearing while the truck drives
 *   GET  /api/roads/replay/live   what the console draws: the readings so far with the road and limit found under each,
 *                                 how far Valhalla has matched, the latest learning run, the speed events
 *
 * Demo only, switched off with gps.admin.reset-enabled=false like the reset itself.
 */
@RestController
@RequestMapping("/api/roads/replay")
public class TripReplayController {

    /** Readings sent to the console at most (the newest are always all there; older ones are thinned). */
    private static final int MAX_POINTS = 4_000;
    private static final int NEWEST_KEPT = 300;

    private final DemoResetService reset;
    private final GpsDeviceSimulatorService simulator;
    private final GpsDeviceSimulatorSettings simulatorSettings;
    private final RoadLibrary library;
    private final RoadLearningService learning;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;

    public TripReplayController(DemoResetService reset, GpsDeviceSimulatorService simulator, GpsDeviceSimulatorSettings simulatorSettings,
                                RoadLibrary library, RoadLearningService learning, NamedParameterJdbcTemplate jdbc,
                                @Value("${gps.admin.reset-enabled:true}") boolean enabled) {
        this.reset = reset;
        this.simulator = simulator;
        this.simulatorSettings = simulatorSettings;
        this.library = library;
        this.learning = learning;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @PostMapping
    public ResponseEntity<?> replay(@RequestParam(defaultValue = "true") boolean forgetRoads) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "The demo reset is switched off (gps.admin.reset-enabled=false)"));
        }
        DemoResetService.ResetReport report = reset.reset(false, false);
        int roadsDeleted = 0;
        if (forgetRoads) {
            roadsDeleted = jdbc.update("DELETE FROM road_segment WHERE manual_limit_kmph IS NULL", new MapSqlParameterSource());
            library.reload();
        }
        learning.forgetCursors();
        SimulationStatus fleet = simulator.startFleet(simulatorSettings.vehicleCount(), simulatorSettings.pingsPerVehicle());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trips", simulatorSettings.tripNumbers());
        body.put("roadsDeleted", roadsDeleted);
        body.put("roadsKept", library.snapshot().roads());
        body.put("tablesEmptied", report.tablesEmptied().size());
        body.put("tookMillis", report.tookMillis());
        body.put("fleet", fleet.lastMessage());
        body.put("notes", report.notes());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/live")
    public Map<String, Object> live() {
        Map<String, Object> body = new LinkedHashMap<>();
        SimulationStatus fleet = simulator.currentStatus();
        body.put("trips", simulatorSettings.tripNumbers());
        body.put("fleet", fleet);

        List<Map<String, Object>> devices = jdbc.queryForList("""
                SELECT p.imei, count(*) AS readings, c.matched_until
                FROM gps_position p LEFT JOIN road_match_cursor c ON c.imei = p.imei
                GROUP BY p.imei, c.matched_until ORDER BY count(*) DESC LIMIT 1
                """, new MapSqlParameterSource());
        Map<String, Object> learningStatus = learning.status();
        body.put("learning", learningStatus);
        if (devices.isEmpty()) {
            body.put("imei", null);
            body.put("readings", List.of());
            body.put("events", List.of());
            return body;
        }
        String imei = (String) devices.getFirst().get("imei");
        Timestamp matchedUntil = (Timestamp) devices.getFirst().get("matched_until");
        body.put("imei", imei);
        body.put("matchedUntil", matchedUntil == null ? null : matchedUntil.toInstant());

        List<Object[]> rows = jdbc.query("""
                SELECT latitude, longitude, speed_kmph, recorded_at FROM gps_position
                WHERE imei = :imei ORDER BY recorded_at
                """, new MapSqlParameterSource("imei", imei),
                (rs, n) -> new Object[]{rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getObject("speed_kmph"),
                        rs.getTimestamp("recorded_at")});
        int total = rows.size();
        int thinFrom = Math.max(0, total - NEWEST_KEPT);
        int step = thinFrom == 0 ? 1 : Math.max(1, (int) Math.ceil(thinFrom / (double) Math.max(1, MAX_POINTS - NEWEST_KEPT)));
        List<Map<String, Object>> readings = new ArrayList<>();
        int onRoad = 0;
        int withLimit = 0;
        for (int i = 0; i < total; i++) {
            Object[] r = rows.get(i);
            RoadLibrary.RoadAt road = library.locate((double) r[0], (double) r[1]);
            if (road != null) {
                onRoad++;
                if (road.limit() != null) {
                    withLimit++;
                }
            }
            if (i < thinFrom && i % step != 0) {
                continue;
            }
            Timestamp at = (Timestamp) r[3];
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("lat", Math.round((double) r[0] * 1e5) / 1e5);
            p.put("lon", Math.round((double) r[1] * 1e5) / 1e5);
            p.put("speed", r[2] instanceof Number n ? Math.round(n.doubleValue()) : null);
            p.put("at", at.toInstant());
            p.put("sent", matchedUntil != null && !at.after(matchedUntil));
            if (road != null) {
                p.put("roadId", road.road().id());
                p.put("road", road.road().label());
                p.put("roadClass", road.road().roadClass());
                p.put("offM", Math.round(road.distanceMeters()));
                p.put("limit", road.limit() == null ? null : road.limit().kmph());
                p.put("source", road.limit() == null ? null : road.limit().source().name());
                p.put("area", road.limit() == null ? null : road.limit().area().name());
            }
            readings.add(p);
        }
        body.put("total", total);
        body.put("onRoad", onRoad);
        body.put("withLimit", withLimit);
        body.put("readings", readings);
        body.put("events", jdbc.queryForList("""
                SELECT event_type AS type, severity, status, started_at AS "startedAt", latitude AS lat, longitude AS lon,
                       details->>'roadLabel' AS road, details->>'roadLimitKmph' AS "roadLimit",
                       details->>'limitSource' AS "limitSource", details->>'limitKmph' AS "appliedLimit",
                       details->>'maxSpeedKmph' AS "peakSpeed", details->>'band' AS band
                FROM vehicle_event WHERE imei = :imei AND event_type IN ('OVERSPEED', 'SPEED_SENSOR_MISMATCH')
                ORDER BY started_at DESC LIMIT 30
                """, new MapSqlParameterSource("imei", imei)));
        return body;
    }
}
