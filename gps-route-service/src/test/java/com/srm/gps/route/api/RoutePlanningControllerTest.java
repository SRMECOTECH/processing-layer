package com.srm.gps.route.api;

import com.srm.gps.route.engine.RoutingAlgorithm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlanningControllerTest {

    @Test
    void algorithmNamesAreForgiving() {
        assertEquals(RoutingAlgorithm.CONTRACTION_HIERARCHIES, RoutePlanningController.algorithm(null));
        assertEquals(RoutingAlgorithm.ASTAR, RoutePlanningController.algorithm("astar"));
        assertEquals(RoutingAlgorithm.DIJKSTRA_BIDIRECTIONAL, RoutePlanningController.algorithm("dijkstra-bidirectional"));
    }

    @Test
    void anUnknownAlgorithmNamesTheValidOnes() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> RoutePlanningController.algorithm("bellman-ford"));
        assertTrue(problem.getMessage().contains("DIJKSTRA"));
    }

    @Test
    void onlyThePlainAlgorithmsSwitchOffBothPreparations() {
        assertTrue(RoutingAlgorithm.CONTRACTION_HIERARCHIES.engineParameters().isEmpty());
        assertEquals(true, RoutingAlgorithm.LANDMARKS_ASTAR.engineParameters().get("ch.disable"));
        assertEquals(null, RoutingAlgorithm.LANDMARKS_ASTAR.engineParameters().get("lm.disable"));
        assertEquals("astar", RoutingAlgorithm.ASTAR.engineParameters().get("algorithm"));
        assertEquals(true, RoutingAlgorithm.ASTAR.engineParameters().get("lm.disable"));
    }

    @Test
    void roundsToMetresAndSeconds() {
        assertEquals(212.35, RouteView.km(212_345.6));
        assertEquals(1.5, RouteView.minutes(90_000));
    }
}
