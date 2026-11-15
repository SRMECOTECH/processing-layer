package com.srm.gps.processing.road;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One piece of road a truck has driven, as Valhalla's map matching (trace_attributes on the all-India OpenStreetMap)
 * described it: one edge of its road graph, kept in PostgreSQL road_segment so the live speed check can find the road
 * under a reading without asking Valhalla.
 *
 * @param id              database id (0 before it is stored)
 * @param key             way + both ends: the same piece seen again gets the same key
 * @param wayId           the OpenStreetMap way the edge belongs to
 * @param name            the road's name (e.g. "Grand Trunk Road"), or null
 * @param ref             its number (e.g. "NH19", "SH4"), or null
 * @param roadClass       Valhalla's class: motorway, trunk, primary, secondary, tertiary, unclassified, residential,
 *                        service_other
 * @param density         Valhalla's road density around the edge, 0..15: how built-up the area is (town vs open road)
 * @param laneCount       lanes in the direction of travel (2+ on one side = a divided multi-lane highway)
 * @param stateCode       the Indian state the edge ends in (JH, UP ...), or null
 * @param osmLimitKmph    the limit tagged in OpenStreetMap (maxspeed), or null: missing on most Indian roads
 * @param manualLimitKmph a limit set by hand on the console, or null; beats every other source
 * @param lengthMeters    the edge's length
 * @param points          its line, [latitude, longitude] pairs
 */
public record RoadSegment(
        long id,
        String key,
        long wayId,
        String name,
        String ref,
        String roadClass,
        int density,
        int laneCount,
        String stateCode,
        Integer osmLimitKmph,
        Integer manualLimitKmph,
        double lengthMeters,
        List<double[]> points) {

    /** NH 19, NH-19, SH 4, MDR 12, AH 1 ... (spaces and dashes are removed). */
    private static final Pattern ROAD_NUMBER = Pattern.compile("^(NH|SH|MDR|ODR|AH|NE)[\\s-]*\\d+[A-Z]?$", Pattern.CASE_INSENSITIVE);

    public RoadSegment {
        points = List.copyOf(points);
        roadClass = roadClass == null || roadClass.isBlank() ? "unclassified" : roadClass.toLowerCase(Locale.ROOT);
    }

    /** What the console shows: "NH19 Grand Trunk Road", "NH19", "Tata Road", or the class when the road has no name. */
    public String label() {
        if (ref != null && name != null) {
            return ref + " " + name;
        }
        if (ref != null) {
            return ref;
        }
        if (name != null) {
            return name;
        }
        return "unnamed " + roadClass;
    }

    /** The first road number among Valhalla's names of an edge (they mix numbers and names), cleaned: "NH 19" -> "NH19". */
    public static String refOf(List<String> names) {
        for (String n : names) {
            String trimmed = n == null ? "" : n.trim();
            if (ROAD_NUMBER.matcher(trimmed).matches()) {
                return trimmed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** The first name that is not a road number, or null. */
    public static String nameOf(List<String> names) {
        for (String n : names) {
            String trimmed = n == null ? "" : n.trim();
            if (!trimmed.isEmpty() && !ROAD_NUMBER.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * The key of an edge: its way and both ends rounded to about a metre, the ends sorted so both directions of a two-way
     * road share one piece (they have the same class and limit).
     */
    public static String keyOf(long wayId, double[] first, double[] last) {
        String a = String.format(Locale.ROOT, "%.5f,%.5f", first[0], first[1]);
        String b = String.format(Locale.ROOT, "%.5f,%.5f", last[0], last[1]);
        return wayId + ":" + (a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a);
    }

    public RoadSegment withId(long newId) {
        return new RoadSegment(newId, key, wayId, name, ref, roadClass, density, laneCount, stateCode, osmLimitKmph,
                manualLimitKmph, lengthMeters, points);
    }
}
