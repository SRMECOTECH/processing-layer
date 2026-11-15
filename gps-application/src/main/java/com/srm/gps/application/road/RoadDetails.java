package com.srm.gps.application.road;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * What Valhalla's map matching says about a road piece beyond what the speed check needs (road_segment, V13): kept for
 * analysis and shown on the console. {@link #attributes} is the whole edge as sent, so a field needed later is already
 * stored.
 *
 * @param traversability forward / backward (one-way in the direction driven) or both (two-way)
 * @param curvature      Valhalla's 0..15, 0 = straight
 * @param valhallaSpeed  the speed Valhalla routes with on this edge (km/h)
 */
public record RoadDetails(String use, String traversability, Boolean bridge, Boolean tunnel, Boolean roundabout, Boolean toll,
                          Integer curvature, Integer valhallaSpeed, String speedType, Boolean truckRoute, String surface,
                          Integer beginHeading, Integer endHeading, String countryCode, String attributes) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static RoadDetails of(Map<String, Object> edge) {
        if (edge == null) {
            return null;
        }
        String json;
        try {
            json = JSON.writeValueAsString(edge);
        } catch (JacksonException notJson) {
            json = null;
        }
        return new RoadDetails(text(edge.get("use"), 30), text(edge.get("traversability"), 10), flag(edge.get("bridge")),
                flag(edge.get("tunnel")), flag(edge.get("roundabout")), flag(edge.get("toll")), whole(edge.get("curvature")),
                positive(edge.get("speed")), text(edge.get("speed_type"), 20), flag(edge.get("truck_route")),
                text(edge.get("surface"), 30), whole(edge.get("begin_heading")), whole(edge.get("end_heading")),
                text(edge.get("country_code"), 5), json);
    }

    /** One-way in the direction it was driven. */
    public boolean oneWay() {
        return traversability != null && !"both".equals(traversability);
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Boolean flag(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Integer whole(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Integer positive(Object value) {
        return value instanceof Number n && n.intValue() > 0 && n.intValue() < 250 ? n.intValue() : null;
    }
}
