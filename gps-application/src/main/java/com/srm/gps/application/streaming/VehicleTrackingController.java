package com.srm.gps.application.streaming;

import com.srm.gps.application.kafkastreams.KafkaStreamsQueryService;
import com.srm.gps.application.processing.DatabaseBackedPreviousReadingStore;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.connectivity.redis.RedisStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the platform remembers about one vehicle, for the vehicle tracking window.
 *
 *   GET /api/vehicles/{imei}/state?history=50
 *     cache        every Redis key of the vehicle, with its type and contents (hashes as field/value)
 *     history      the newest readings from the Redis stream gps:history:{imei}
 *     events       the vehicle's latest events from Redis gps:events:{imei}
 *     processing   the previous reading the normalizer measures the next one from: the Kafka Streams state store
 *                  when Kafka Streams owns normalization, otherwise the listener's memory
 *     tripMotion   the trip detector's state row (PostgreSQL vehicle_motion_state)
 */
@RestController
public class VehicleTrackingController {

    private final RedisStateStore redis;
    private final DatabaseBackedPreviousReadingStore previousReadings;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<KafkaStreamsQueryService> kafkaStreams;

    public VehicleTrackingController(RedisStateStore redis, DatabaseBackedPreviousReadingStore previousReadings,
                                     NamedParameterJdbcTemplate jdbc, ObjectProvider<KafkaStreamsQueryService> kafkaStreams) {
        this.redis = redis;
        this.previousReadings = previousReadings;
        this.jdbc = jdbc;
        this.kafkaStreams = kafkaStreams;
    }

    public record CacheEntry(String key, String type, String purpose, Object value) {
    }

    @GetMapping("/api/vehicles/{imei}/state")
    public Map<String, Object> state(@PathVariable String imei, @RequestParam(defaultValue = "50") int history) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("imei", imei);

        List<CacheEntry> cache = new ArrayList<>();
        addHash(cache, RedisKeyNames.liveVehicle(imei), "Latest position (live-location consumer)");
        addHash(cache, RedisKeyNames.vehicleState(imei), "Vehicle state: heading, Kalman track, counters (vehicle-state consumer)");
        addHash(cache, RedisKeyNames.speedState(imei), "Speed-violation state (speed-violation consumer)");
        addHash(cache, RedisKeyNames.ignitionState(imei), "Ignition, engine hours, idling (ignition consumer)");
        addHash(cache, RedisKeyNames.stoppageState(imei), "Stoppage detector state (stoppage consumer)");
        cache.add(new CacheEntry(RedisKeyNames.history(imei), "stream", "History of readings (capped stream)",
                Map.of("length", redis.historyLength(imei))));
        cache.add(new CacheEntry(RedisKeyNames.events(imei), "list", "Latest events (capped list)",
                Map.of("length", redis.template().opsForList().size(RedisKeyNames.events(imei)))));
        answer.put("cache", cache);

        answer.put("history", redis.readHistory(imei, Math.clamp(history, 1, 500)));
        List<Object> events = new ArrayList<>();
        for (String json : redis.recentEvents(imei, 20)) {
            events.add(GpsMessageJson.fromJson(json, LinkedHashMap.class));
        }
        answer.put("events", events);
        KafkaStreamsQueryService streams = kafkaStreams.getIfAvailable();
        answer.put("processing", Optional.ofNullable(streams).flatMap(s -> s.previousReading(imei))
                .or(() -> previousReadings.findInMemory(imei)).orElse(null));
        answer.put("tripMotion", tripMotion(imei));
        return answer;
    }

    /**
     * GET /api/vehicles/states?limit=200 - the vehicle-state hash of every vehicle (without the Kalman matrices),
     * for lists and comparisons (raw against Kalman distance).
     */
    @GetMapping("/api/vehicles/states")
    public List<Map<String, String>> states(@RequestParam(defaultValue = "200") int limit) {
        List<Map<String, String>> states = new ArrayList<>();
        for (String key : redis.scanKeys(RedisKeyNames.vehicleState("*"))) {
            if (states.size() >= Math.clamp(limit, 1, 1000)) {
                break;
            }
            Map<String, String> fields = redis.readHash(key);
            fields.remove("kalmanState");
            fields.remove("kalmanCovariance");
            states.add(fields);
        }
        return states;
    }

    private void addHash(List<CacheEntry> cache, String key, String purpose) {
        Map<String, String> fields = redis.readHash(key);
        cache.add(new CacheEntry(key, fields.isEmpty() ? "none" : "hash", purpose, fields));
    }

    private Map<String, Object> tripMotion(String imei) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT imei, tenant_code, vehicle_registration_no, motion_streak, event_recorded_at, event_latitude,
                       event_longitude, last_recorded_at, reading_window::text AS reading_window,
                       open_trip::text AS open_trip, open_stop::text AS open_stop, updated_at
                FROM vehicle_motion_state WHERE imei = :imei
                """, new MapSqlParameterSource("imei", imei));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        for (String json : List.of("reading_window", "open_trip", "open_stop")) {
            Object text = row.get(json);
            row.put(json, text == null ? null : GpsMessageJson.fromJson(text.toString(), Object.class));
        }
        return row;
    }
}
