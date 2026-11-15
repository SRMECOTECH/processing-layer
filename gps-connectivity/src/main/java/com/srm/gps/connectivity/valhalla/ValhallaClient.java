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
 *   POST /trace_attributes   map matching: the GPS points of a drive snapped onto the roads, with each road edge's class,
 *                  names, OpenStreetMap speed limit, lanes, density and state
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

    /**
     * One road edge of a map-matched drive.
     *
     * @param beginShapeIndex first point of the matched shape on this edge
     * @param endShapeIndex   last point of the matched shape on this edge
     * @param speedLimitKmph  OpenStreetMap's maxspeed, or null when the edge has none
     * @param density         Valhalla's road density, 0..15 (higher = more built-up)
     * @param stateCode       the state of the edge's end node (admins list), or null
     */
    public record TraceEdge(long wayId, List<String> names, String roadClass, Integer speedLimitKmph, double lengthKm, String use,
                            int density, int laneCount, String stateCode, int beginShapeIndex, int endShapeIndex) {
    }

    /** A map-matched drive: its edges and the matched line (encoded polyline, precision 6). */
    public record Trace(List<TraceEdge> edges, String shape, int matchedPoints, int unmatchedPoints, long millis) {
    }

    private static final List<String> TRACE_ATTRIBUTES = List.of(
            "edge.way_id", "edge.names", "edge.road_class", "edge.speed_limit", "edge.length", "edge.use", "edge.density",
            "edge.lane_count", "edge.begin_shape_index", "edge.end_shape_index", "edge.end_node.admin_index",
            "admin.state_code", "admin.country_code", "matched.type", "shape");

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

    /**
     * Map matching (Valhalla's Meili, hidden Markov model over the road graph): which roads the points were driven on.
     * Valhalla refuses a drive longer than its trace limit (200 km by default), so the caller sends pieces.
     *
     * @param points       [latitude, longitude] in driving order
     * @param searchRadius how far (m) from a point a road may be
     */
    public Trace trace(String baseUrl, List<double[]> points, String costing, int searchRadius) throws IOException, InterruptedException {
        List<Map<String, Object>> shape = new ArrayList<>();
        for (double[] p : points) {
            shape.add(Map.of("lat", p[0], "lon", p[1]));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shape", shape);
        request.put("costing", costing);
        request.put("shape_match", "map_snap");
        request.put("trace_options", Map.of("search_radius", searchRadius));
        request.put("filters", Map.of("attributes", TRACE_ATTRIBUTES, "action", "include"));
        JsonHttp.Answer answer = http.post(JsonHttp.baseUrl(baseUrl) + "/trace_attributes", request);
        Map<String, Object> body = answer.body();

        List<String> states = new ArrayList<>();
        if (body.get("admins") instanceof List<?> admins) {
            for (Object admin : admins) {
                states.add(admin instanceof Map<?, ?> m && m.get("state_code") instanceof String code && !code.isBlank() ? code : null);
            }
        }
        List<TraceEdge> edges = new ArrayList<>();
        if (body.get("edges") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> e)) {
                    continue;
                }
                List<String> names = new ArrayList<>();
                if (e.get("names") instanceof List<?> n) {
                    n.forEach(name -> names.add(String.valueOf(name)));
                }
                Integer limit = e.get("speed_limit") instanceof Number n && n.intValue() > 0 && n.intValue() < 200 ? n.intValue() : null;
                String state = null;
                if (e.get("end_node") instanceof Map<?, ?> node && node.get("admin_index") instanceof Number index
                        && index.intValue() >= 0 && index.intValue() < states.size()) {
                    state = states.get(index.intValue());
                }
                edges.add(new TraceEdge(e.get("way_id") instanceof Number w ? w.longValue() : 0, names,
                        e.get("road_class") instanceof String rc ? rc : null, limit, number(e.get("length")),
                        e.get("use") instanceof String u ? u : null, (int) number(e.get("density")), (int) number(e.get("lane_count")),
                        state, (int) number(e.get("begin_shape_index")), (int) number(e.get("end_shape_index"))));
            }
        }
        int matched = 0;
        int unmatched = 0;
        if (body.get("matched_points") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && "unmatched".equals(m.get("type"))) {
                    unmatched++;
                } else {
                    matched++;
                }
            }
        }
        String matchedShape = body.get("shape") instanceof String text ? text : "";
        return new Trace(edges, matchedShape, matched, unmatched, answer.millis());
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
