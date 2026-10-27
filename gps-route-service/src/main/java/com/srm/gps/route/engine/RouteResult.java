package com.srm.gps.route.engine;

import java.util.List;
import java.util.Map;

/**
 * The engine's answer to one {@link RouteQuery}.
 *
 * @param query          what was asked
 * @param paths          the best route first; more than one only for {@link RoutingAlgorithm#ALTERNATIVES}
 * @param visitedNodes   road junctions the search looked at - the work the algorithm had to do
 * @param engineMillis   time the engine spent searching
 * @param roadDataDate   date of the OpenStreetMap data the graph was built from, or null
 */
public record RouteResult(RouteQuery query, List<Path> paths, long visitedNodes, long engineMillis, String roadDataDate) {

    public Path best() {
        return paths.getFirst();
    }

    /**
     * One route.
     *
     * @param distanceMeters           length
     * @param durationMillis           driving time for the profile (no stops, no traffic)
     * @param points                   the road geometry, start to destination
     * @param instructions             turn-by-turn, empty when not requested
     * @param metersByRoadClass        length per OpenStreetMap road class (TRUNK = national highways in India)
     * @param tollMeters               length on roads tagged as tolled
     */
    public record Path(double distanceMeters, long durationMillis, List<GeoPoint> points, List<Instruction> instructions,
                       Map<String, Double> metersByRoadClass, double tollMeters) {
    }

    /** One turn-by-turn step; sign as GraphHopper defines it (0 continue, -2 left, 2 right, 4 finish ...). */
    public record Instruction(String text, String streetName, double distanceMeters, long durationMillis, int sign) {
    }
}
