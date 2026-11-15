package com.srm.gps.application.road;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A point on the road: the end node of a matched edge as Valhalla's map matching describes it (road_point, V14).
 *
 * @param type        Valhalla's node type: street_intersection, toll_booth, gate, border_control, bollard ...
 * @param sideRoads   roads meeting here besides the one driven
 * @param segmentKey  the road piece driven into the point
 */
public record RoadPoint(String key, double latitude, double longitude, String type, boolean trafficSignal, boolean fork,
                        int sideRoads, String sideRoadClasses, String intersecting, String segmentKey) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The point at the end of an edge, or null when Valhalla sent no end node. */
    public static RoadPoint of(Object endNode, double[] position, String segmentKey) {
        if (!(endNode instanceof Map<?, ?> node) || position == null) {
            return null;
        }
        String type = node.get("type") instanceof String t && !t.isBlank() ? t : "unknown";
        List<?> edges = node.get("intersecting_edges") instanceof List<?> list ? list : List.of();
        StringJoiner classes = new StringJoiner(",");
        for (Object e : edges) {
            if (e instanceof Map<?, ?> m && m.get("road_class") instanceof String rc) {
                classes.add(rc);
            }
        }
        String json;
        try {
            json = edges.isEmpty() ? null : JSON.writeValueAsString(edges);
        } catch (JacksonException notJson) {
            json = null;
        }
        String joined = classes.toString();
        return new RoadPoint(keyOf(position), position[0], position[1], type.length() > 30 ? type.substring(0, 30) : type,
                Boolean.TRUE.equals(node.get("traffic_signal")), Boolean.TRUE.equals(node.get("fork")), edges.size(),
                joined.isEmpty() ? null : joined.length() > 200 ? joined.substring(0, 200) : joined, json, segmentKey);
    }

    public static String keyOf(double[] position) {
        return String.format(Locale.ROOT, "%.5f,%.5f", position[0], position[1]);
    }
}
