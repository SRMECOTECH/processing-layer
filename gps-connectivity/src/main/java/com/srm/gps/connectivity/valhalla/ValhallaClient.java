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

    /** One instruction of a leg: the shape points begin..end take this long (Valhalla's truck speed for those roads). */
    public record Maneuver(int beginShapeIndex, int endShapeIndex, double timeSeconds, double lengthKm) {
    }

    public record Leg(String shape, double lengthKm, double timeSeconds, List<Maneuver> maneuvers) {
    }

    /** A route with everything a route plan needs: each leg's shape and maneuver timings. */
    public record Plan(double lengthKm, double timeSeconds, List<Leg> legs, long millis) {
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

    /**
     * A route plan (bidirectional A* on Valhalla's tiled road graph) through the locations in order.
     *
     * @param startHeading the vehicle's heading at the first location (degrees from north), so the plan does not begin
     *                     with a U-turn; null when unknown
     */
    public Plan plan(String baseUrl, List<Location> locations, String costing, Double startHeading) throws IOException, InterruptedException {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < locations.size(); i++) {
            Location location = locations.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("lat", location.latitude());
            point.put("lon", location.longitude());
            if (i == 0 && startHeading != null && !startHeading.isNaN()) {
                point.put("heading", Math.round(startHeading));
                point.put("heading_tolerance", 60);
            }
            points.add(point);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("locations", points);
        request.put("costing", costing);
        request.put("units", "kilometers");
        // maneuvers (with their shape indices and times) but no narrative text; narrative:false would drop the maneuvers
        request.put("directions_type", "maneuvers");
        JsonHttp.Answer answer = http.post(JsonHttp.baseUrl(baseUrl) + "/route", request);

        Map<?, ?> trip = answer.body().get("trip") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> summary = trip.get("summary") instanceof Map<?, ?> map ? map : Map.of();
        if (!(summary.get("length") instanceof Number length) || !(summary.get("time") instanceof Number time)) {
            throw new IOException("Valhalla answered without a route summary");
        }
        List<Leg> legs = new ArrayList<>();
        if (trip.get("legs") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> leg) || !(leg.get("shape") instanceof String shape)) {
                    continue;
                }
                Map<?, ?> legSummary = leg.get("summary") instanceof Map<?, ?> map ? map : Map.of();
                List<Maneuver> maneuvers = new ArrayList<>();
                if (leg.get("maneuvers") instanceof List<?> steps) {
                    for (Object step : steps) {
                        if (step instanceof Map<?, ?> m && m.get("begin_shape_index") instanceof Number begin
                                && m.get("end_shape_index") instanceof Number end) {
                            maneuvers.add(new Maneuver(begin.intValue(), end.intValue(), number(m.get("time")), number(m.get("length"))));
                        }
                    }
                }
                legs.add(new Leg(shape, number(legSummary.get("length")), number(legSummary.get("time")), maneuvers));
            }
        }
        if (legs.isEmpty()) {
            throw new IOException("Valhalla answered without route legs");
        }
        return new Plan(length.doubleValue(), time.doubleValue(), legs, answer.millis());
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
