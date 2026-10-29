package com.srm.gps.route.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.rule.DistanceCalculator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GraphHopper's JSON in and out (POST /route, error bodies) - plain functions, so they are tested without an engine.
 * The format is GraphHopper 11's routing API: docs/web/api-doc.md in the GraphHopper repository.
 */
final class GraphHopperMessages {

    /** Path details asked for every route: what kind of road each stretch is, and whether it is tolled. */
    static final List<String> PATH_DETAILS = List.of("road_class", "toll");

    private GraphHopperMessages() {
    }

    /** The POST /route body. Points go as [longitude, latitude], GraphHopper's order. */
    static Map<String, Object> routeRequest(RouteQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", query.profile());
        body.put("points", query.points().stream().map(point -> List.of(point.longitude(), point.latitude())).toList());
        body.put("points_encoded", false);
        body.put("instructions", query.instructions());
        body.put("locale", "en");
        body.put("details", PATH_DETAILS);
        body.putAll(query.algorithm().engineParameters());
        return body;
    }

    static RouteResult readRoute(RouteQuery query, String json) {
        RouteResponse response = GpsMessageJson.fromJson(json, RouteResponse.class);
        if (response.paths() == null || response.paths().isEmpty()) {
            throw new RoutingEngineException("The routing engine returned no route");
        }
        List<RouteResult.Path> paths = response.paths().stream().map(GraphHopperMessages::readPath).toList();
        Map<String, Object> hints = response.hints() != null ? response.hints() : Map.of();
        Map<String, Object> info = response.info() != null ? response.info() : Map.of();
        return new RouteResult(query, paths, number(hints.get("visited_nodes.sum")), number(info.get("took")),
                info.get("road_data_timestamp") != null ? info.get("road_data_timestamp").toString() : null);
    }

    /** The "message" of an error body, or the body itself when it is not GraphHopper's error JSON. */
    static String errorMessage(String json) {
        try {
            ErrorResponse error = GpsMessageJson.fromJson(json, ErrorResponse.class);
            if (error.message() != null && !error.message().isBlank()) {
                return error.message();
            }
        } catch (GpsMessageJson.InvalidGpsMessageException notJson) {
            // fall through to the raw body
        }
        return json == null || json.isBlank() ? "no message" : json.strip();
    }

    private static RouteResult.Path readPath(PathResponse path) {
        List<GeoPoint> points = new ArrayList<>();
        if (path.points() != null && path.points().coordinates() != null) {
            for (List<Double> lonLat : path.points().coordinates()) {
                points.add(new GeoPoint(lonLat.get(1), lonLat.get(0)));
            }
        }
        List<RouteResult.Instruction> instructions = path.instructions() == null ? List.of()
                : path.instructions().stream()
                        .map(i -> new RouteResult.Instruction(i.text(), i.streetName(), i.distance(), i.time(), i.sign()))
                        .toList();
        Map<String, List<List<Object>>> details = path.details() != null ? path.details() : Map.of();

        Map<String, Double> metersByRoadClass = metersByValue(points, details.get("road_class"));
        double tollMeters = metersByValue(points, details.get("toll")).entrySet().stream()
                .filter(entry -> !entry.getKey().equals("NO") && !entry.getKey().equals("MISSING"))
                .mapToDouble(Map.Entry::getValue).sum();
        return new RouteResult.Path(path.distance(), path.time(), points, instructions, metersByRoadClass, tollMeters);
    }

    /**
     * Adds up the length of every [fromIndex, toIndex, value] interval of a path detail, per value (upper case).
     * The indexes point into the route's points; a stretch is measured point to point along the road.
     */
    static Map<String, Double> metersByValue(List<GeoPoint> points, List<List<Object>> intervals) {
        Map<String, Double> meters = new LinkedHashMap<>();
        if (intervals == null) {
            return meters;
        }
        for (List<Object> interval : intervals) {
            int from = (int) number(interval.get(0));
            int to = (int) Math.min(number(interval.get(1)), points.size() - 1);
            String value = interval.get(2) == null ? "MISSING" : interval.get(2).toString().toUpperCase(Locale.ROOT);
            double length = 0;
            for (int i = from; i < to; i++) {
                GeoPoint a = points.get(i);
                GeoPoint b = points.get(i + 1);
                length += DistanceCalculator.distance(a.latitude(), a.longitude(), b.latitude(), b.longitude());
            }
            meters.merge(value, length, Double::sum);
        }
        return meters;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    // ---------------------------------------------------------------- GraphHopper's JSON (only the fields we read)

    record RouteResponse(Map<String, Object> hints, Map<String, Object> info, List<PathResponse> paths) {
    }

    record PathResponse(double distance, long time, PointsResponse points, List<InstructionResponse> instructions,
                        Map<String, List<List<Object>>> details) {
    }

    /** points_encoded=false: a GeoJSON LineString, coordinates as [longitude, latitude]. */
    record PointsResponse(List<List<Double>> coordinates) {
    }

    record InstructionResponse(String text, @JsonProperty("street_name") String streetName, double distance, long time,
                               int sign) {
    }

    record ErrorResponse(String message) {
    }
}
