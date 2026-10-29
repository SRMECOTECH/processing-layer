package com.srm.gps.route.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphHopperMessagesTest {

    private static final GeoPoint JAMSHEDPUR = new GeoPoint(22.8046, 86.2029);
    private static final GeoPoint KOLKATA = new GeoPoint(22.5726, 88.3639);

    /** Shape of a GraphHopper 11 POST /route answer with points_encoded=false and two path details (trimmed). */
    private static final String ROUTE_RESPONSE = """
            {
              "hints": {"visited_nodes.sum": 412, "visited_nodes.average": 412.0},
              "info": {"copyrights": ["GraphHopper", "OpenStreetMap contributors"], "took": 7,
                       "road_data_timestamp": "2026-10-25T20:21:01Z"},
              "paths": [{
                "distance": 2226.4,
                "time": 120000,
                "points_encoded": false,
                "points": {"type": "LineString", "coordinates": [[86.20, 22.80], [86.21, 22.80], [86.22, 22.80]]},
                "instructions": [
                  {"distance": 1113.2, "sign": 0, "interval": [0, 1], "text": "Continue onto NH18", "time": 60000, "street_name": "NH18"},
                  {"distance": 0.0, "sign": 4, "interval": [2, 2], "text": "Arrive at destination", "time": 0, "street_name": ""}
                ],
                "details": {
                  "road_class": [[0, 1, "trunk"], [1, 2, "primary"]],
                  "toll": [[0, 1, "all"], [1, 2, "no"]]
                }
              }]
            }
            """;

    @Test
    void routeRequestSendsLongitudeFirstAndTheAlgorithmParameters() {
        RouteQuery query = new RouteQuery(List.of(JAMSHEDPUR, KOLKATA), "truck", RoutingAlgorithm.DIJKSTRA, false);

        Map<String, Object> body = GraphHopperMessages.routeRequest(query);

        assertEquals(List.of(List.of(86.2029, 22.8046), List.of(88.3639, 22.5726)), body.get("points"));
        assertEquals("truck", body.get("profile"));
        assertEquals(false, body.get("points_encoded"));
        assertEquals(true, body.get("ch.disable"));
        assertEquals(true, body.get("lm.disable"));
        assertEquals("dijkstra", body.get("algorithm"));
    }

    @Test
    void readsDistanceTimeWorkAndGeometry() {
        RouteQuery query = new RouteQuery(List.of(JAMSHEDPUR, KOLKATA), "truck", null, true);

        RouteResult result = GraphHopperMessages.readRoute(query, ROUTE_RESPONSE);

        assertEquals(RoutingAlgorithm.CONTRACTION_HIERARCHIES, result.query().algorithm());
        assertEquals(412, result.visitedNodes());
        assertEquals(7, result.engineMillis());
        assertEquals("2026-10-25T20:21:01Z", result.roadDataDate());
        RouteResult.Path path = result.best();
        assertEquals(2226.4, path.distanceMeters());
        assertEquals(120_000, path.durationMillis());
        assertEquals(new GeoPoint(22.80, 86.20), path.points().getFirst());
        assertEquals(3, path.points().size());
        assertEquals("NH18", path.instructions().getFirst().streetName());
        assertEquals(4, path.instructions().getLast().sign());
    }

    @Test
    void addsUpRoadClassAndTollLengthsAlongThePoints() {
        RouteResult.Path path = GraphHopperMessages.readRoute(
                new RouteQuery(List.of(JAMSHEDPUR, KOLKATA), "truck", null, true), ROUTE_RESPONSE).best();

        // 0.01 degree of longitude at latitude 22.8 is about 1,027 m
        double trunk = path.metersByRoadClass().get("TRUNK");
        double primary = path.metersByRoadClass().get("PRIMARY");
        assertTrue(trunk > 1000 && trunk < 1050, "trunk " + trunk);
        assertEquals(trunk, primary, 1.0);
        assertEquals(trunk, path.tollMeters(), 0.001);
    }

    @Test
    void detailIntervalsBeyondThePointsAreClamped() {
        List<GeoPoint> points = List.of(new GeoPoint(22.8, 86.2), new GeoPoint(22.8, 86.21));

        Map<String, Double> meters = GraphHopperMessages.metersByValue(points, List.of(List.of(0, 5, "trunk")));

        assertEquals(1, meters.size());
        assertTrue(meters.get("TRUNK") > 1000);
    }

    @Test
    void readsTheMessageOfAnErrorBody() {
        assertEquals("Cannot find point 0: 95.0,86.2",
                GraphHopperMessages.errorMessage("{\"message\":\"Cannot find point 0: 95.0,86.2\",\"hints\":[]}"));
        assertEquals("Bad Gateway", GraphHopperMessages.errorMessage("Bad Gateway"));
        assertFalse(GraphHopperMessages.errorMessage("").isBlank());
    }
}
