package com.srm.gps.connectivity.valhalla;

import com.srm.gps.connectivity.http.JsonHttp;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Valhalla routing engine: the all-India OpenStreetMap on the HP home server (default http://192.168.137.1:8002).
 * Every call takes the base URL, because the address is changed from the console while the service runs.
 *
 *   GET  /status   version, when the map tiles were built, the actions it offers
 *   POST /route    A -> B (+ waypoints): length (km), time (s), each leg's shape as an encoded polyline (precision 6)
 *
 * Costing: auto | truck | bicycle | pedestrian | motor_scooter | bus.
 */
public class ValhallaClient {

    /** @param tilesBuilt when the map tiles were last built (null if Valhalla does not say) */
    public record Status(String version, Instant tilesBuilt, List<String> actions, long millis) {
    }

    public record Location(double latitude, double longitude) {
    }

    /** @param legShapes one encoded polyline (precision 6) per leg between two locations */
    public record Route(double lengthKm, double timeSeconds, List<String> legShapes, long millis) {
    }

    private final JsonHttp http;

    public ValhallaClient(JsonHttp http) {
        this.http = http;
    }

    public Status status(String baseUrl) throws IOException, InterruptedException {
        JsonHttp.Answer answer = http.get(JsonHttp.baseUrl(baseUrl) + "/status");
        Map<String, Object> body = answer.body();
        List<String> actions = new ArrayList<>();
        if (body.get("available_actions") instanceof List<?> list) {
            list.forEach(action -> actions.add(String.valueOf(action)));
        }
        Instant tilesBuilt = body.get("tileset_last_modified") instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : null;
        return new Status(String.valueOf(body.getOrDefault("version", "?")), tilesBuilt, actions, answer.millis());
    }

    public Route route(String baseUrl, List<Location> locations, String costing) throws IOException, InterruptedException {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Location location : locations) {
            points.add(Map.of("lat", location.latitude(), "lon", location.longitude()));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("locations", points);
        request.put("costing", costing);
        request.put("units", "kilometers");
        JsonHttp.Answer answer = http.post(JsonHttp.baseUrl(baseUrl) + "/route", request);

        Map<?, ?> trip = answer.body().get("trip") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> summary = trip.get("summary") instanceof Map<?, ?> map ? map : Map.of();
        List<String> shapes = new ArrayList<>();
        if (trip.get("legs") instanceof List<?> legs) {
            for (Object leg : legs) {
                if (leg instanceof Map<?, ?> legMap && legMap.get("shape") instanceof String shape) {
                    shapes.add(shape);
                }
            }
        }
        if (!(summary.get("length") instanceof Number length) || !(summary.get("time") instanceof Number time)) {
            throw new IOException("Valhalla answered without a route summary");
        }
        return new Route(length.doubleValue(), time.doubleValue(), shapes, answer.millis());
    }
}
