package com.srm.gps.route.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the routing engine searches the road graph. Every one finds the best route for the profile; they differ in
 * how much of the map they have to look at (visited nodes) and so in how fast they answer.
 *
 * GraphHopper picks the search from request parameters: CH unless ch.disable=true, then LM unless lm.disable=true,
 * then the named textbook algorithm ("flexible mode"). Which profiles have CH and LM is set in
 * infrastructure/routing/graphhopper/config.yml (CH: truck and car, LM: truck).
 */
public enum RoutingAlgorithm {

    CONTRACTION_HIERARCHIES("Contraction Hierarchies",
            "Bidirectional Dijkstra over shortcuts prepared at import. Milliseconds across India: the default for "
                    + "planning and for recalculating a route the moment a truck leaves it.",
            Map.of()),

    LANDMARKS_ASTAR("A* with landmarks",
            "Bidirectional A* whose estimate comes from distances to 16 landmarks prepared at import. Slower than CH, "
                    + "but accepts a custom model per request (avoid tolls, a closed road). Truck profile only.",
            Map.of("ch.disable", true, "algorithm", "astarbi")),

    ASTAR_BIDIRECTIONAL("Bidirectional A*",
            "A* from both ends at once, guided by the straight-line distance. No preparation.",
            flexible("astarbi")),

    ASTAR("A*",
            "Dijkstra plus a straight-line estimate to the goal, so it searches towards the destination. No preparation.",
            flexible("astar")),

    DIJKSTRA_BIDIRECTIONAL("Bidirectional Dijkstra",
            "Dijkstra from both ends until the two searches meet: about half the area of plain Dijkstra. No preparation.",
            flexible("dijkstrabi")),

    DIJKSTRA("Dijkstra",
            "The textbook shortest path: grows a circle of settled roads around the start until it reaches the goal. "
                    + "Exact, and the slowest - the reference the others are measured against.",
            flexible("dijkstra")),

    ALTERNATIVES("Alternative routes",
            "Up to three clearly different routes (at most 40% longer, sharing at most 60% of the best one): the "
                    + "choices a dispatcher compares, and the corridors a deviation can still be accepted on.",
            Map.of("algorithm", "alternative_route", "alternative_route.max_paths", 3));

    private final String label;
    private final String description;
    private final Map<String, Object> engineParameters;

    RoutingAlgorithm(String label, String description, Map<String, Object> engineParameters) {
        this.label = label;
        this.description = description;
        this.engineParameters = engineParameters;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The request fields that make GraphHopper search this way (added to the POST /route body). */
    public Map<String, Object> engineParameters() {
        return engineParameters;
    }

    private static Map<String, Object> flexible(String algorithm) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("ch.disable", true);
        parameters.put("lm.disable", true);
        parameters.put("algorithm", algorithm);
        return Map.copyOf(parameters);
    }
}
