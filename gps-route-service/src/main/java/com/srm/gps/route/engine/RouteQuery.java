package com.srm.gps.route.engine;

import java.util.List;

/**
 * One route request: start, optional via points, destination.
 *
 * @param points       start, via points in order, destination (at least two)
 * @param profile      what is driving: truck or car (config.yml)
 * @param algorithm    how the engine searches
 * @param instructions whether turn-by-turn instructions are wanted
 */
public record RouteQuery(List<GeoPoint> points, String profile, RoutingAlgorithm algorithm, boolean instructions) {

    public RouteQuery {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("A route needs a start and a destination");
        }
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("A route needs a profile (truck or car)");
        }
        points = List.copyOf(points);
        algorithm = algorithm != null ? algorithm : RoutingAlgorithm.CONTRACTION_HIERARCHIES;
    }
}
