package com.srm.gps.route.api;

import com.srm.gps.route.engine.RouteResult;
import com.srm.gps.route.engine.RoutingAlgorithm;

/**
 * One algorithm's run in GET /api/routing/compare: same start, same destination, same profile.
 * The best route should be the same length for every algorithm; visitedNodes and engineMillis show the work.
 *
 * @param error why the engine refused this one (for example LANDMARKS_ASTAR on a profile without landmarks,
 *              or plain DIJKSTRA hitting the visited-nodes guard across India), else null
 */
public record AlgorithmRunView(String algorithm, String label, String description, Double distanceKm,
                               Double durationMinutes, Long visitedNodes, Long engineMillis, long roundTripMillis,
                               String error) {

    static AlgorithmRunView of(RoutingAlgorithm algorithm, RouteResult result, long roundTripMillis) {
        return new AlgorithmRunView(algorithm.name(), algorithm.label(), algorithm.description(),
                RouteView.km(result.best().distanceMeters()), RouteView.minutes(result.best().durationMillis()),
                result.visitedNodes(), result.engineMillis(), roundTripMillis, null);
    }

    static AlgorithmRunView failed(RoutingAlgorithm algorithm, String error, long roundTripMillis) {
        return new AlgorithmRunView(algorithm.name(), algorithm.label(), algorithm.description(), null, null, null,
                null, roundTripMillis, error);
    }
}
