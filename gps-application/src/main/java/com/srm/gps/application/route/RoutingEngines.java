package com.srm.gps.application.route;

import com.srm.gps.application.connections.ConnectionsService;
import com.srm.gps.connectivity.graphhopper.GraphHopperClient;
import com.srm.gps.connectivity.http.JsonHttp;
import com.srm.gps.connectivity.valhalla.ValhallaClient;
import com.srm.gps.processing.route.Polyline6;
import com.srm.gps.processing.route.RoutePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The road routing engines a plan can come from, in order:
 *
 *   1. Valhalla, all-India OpenStreetMap on the HP home server (address from Developer -> Connections), costing truck:
 *      bidirectional A* over its hierarchical tiles.
 *   2. GraphHopper on this laptop (compose profile "routing", gps.routing.graphhopper-url), profile truck: contraction
 *      hierarchies. Used when Valhalla does not answer (the HP asleep or off the hotspot).
 *
 * Both give the line and per-instruction times, so the plan's planned seconds follow each road's own speed.
 */
@Component
public class RoutingEngines {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingEngines.class);

    /** The engine's answer as one line through every location, with the timings of its instructions. */
    public record EngineRoute(String engine, List<double[]> points, List<RoutePlan.Timing> timings, double lengthMeters,
                              double seconds, List<Integer> legEnds, long millis) {
    }

    private final ConnectionsService connections;
    private final ValhallaClient valhalla;
    private final GraphHopperClient graphHopper;
    private final String graphHopperUrl;
    private final String costing;

    public RoutingEngines(ConnectionsService connections,
                          @Value("${gps.routing.graphhopper-url:}") String graphHopperUrl,
                          @Value("${gps.routing.costing:truck}") String costing,
                          @Value("${gps.routing.timeout:PT20S}") Duration timeout) {
        JsonHttp http = new JsonHttp(timeout);
        this.connections = connections;
        this.valhalla = new ValhallaClient(http);
        this.graphHopper = new GraphHopperClient(http);
        this.graphHopperUrl = graphHopperUrl == null ? "" : graphHopperUrl.trim();
        this.costing = costing;
    }

    /**
     * @param latLon       the locations in order, {latitude, longitude}: start, stops, destination
     * @param startHeading the vehicle's heading at the start (no U-turn first), or NaN
     */
    public EngineRoute route(List<double[]> latLon, double startHeading) throws IOException {
        IOException valhallaProblem;
        try {
            return fromValhalla(latLon, startHeading);
        } catch (IOException | RuntimeException problem) {
            valhallaProblem = new IOException("Valhalla at " + connections.valhallaUrl() + ": " + problem.getMessage(), problem);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while asking Valhalla");
        }
        if (graphHopperUrl.isEmpty()) {
            throw valhallaProblem;
        }
        try {
            EngineRoute route = fromGraphHopper(latLon);
            LOG.info("Route from GraphHopper ({}): {}", graphHopperUrl, valhallaProblem.getMessage());
            return route;
        } catch (IOException | RuntimeException problem) {
            throw new IOException(valhallaProblem.getMessage() + "; GraphHopper at " + graphHopperUrl + ": " + problem.getMessage(),
                    problem);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while asking GraphHopper");
        }
    }

    private EngineRoute fromValhalla(List<double[]> latLon, double startHeading) throws IOException, InterruptedException {
        List<ValhallaClient.Location> locations = new ArrayList<>();
        latLon.forEach(p -> locations.add(new ValhallaClient.Location(p[0], p[1])));
        ValhallaClient.Plan plan = valhalla.plan(connections.valhallaUrl(), locations, costing,
                Double.isNaN(startHeading) ? null : startHeading);
        List<double[]> points = new ArrayList<>();
        List<RoutePlan.Timing> timings = new ArrayList<>();
        List<Integer> legEnds = new ArrayList<>();
        for (ValhallaClient.Leg leg : plan.legs()) {
            List<double[]> shape = Polyline6.decode(leg.shape());
            // a leg starts where the previous one ended: that point is not repeated
            int base = points.isEmpty() ? 0 : points.size() - 1;
            points.addAll(points.isEmpty() ? shape : shape.subList(1, shape.size()));
            for (ValhallaClient.Maneuver m : leg.maneuvers()) {
                if (m.endShapeIndex() > m.beginShapeIndex()) {
                    timings.add(new RoutePlan.Timing(base + m.beginShapeIndex(), base + m.endShapeIndex(), m.timeSeconds()));
                }
            }
            legEnds.add(points.size() - 1);
        }
        return new EngineRoute("valhalla", points, timings, plan.lengthKm() * 1000, plan.timeSeconds(), legEnds, plan.millis());
    }

    private EngineRoute fromGraphHopper(List<double[]> latLon) throws IOException, InterruptedException {
        GraphHopperClient.Path path = graphHopper.route(graphHopperUrl, latLon, costing);
        List<RoutePlan.Timing> timings = new ArrayList<>();
        for (GraphHopperClient.Instruction i : path.instructions()) {
            if (i.toIndex() > i.fromIndex()) {
                timings.add(new RoutePlan.Timing(i.fromIndex(), i.toIndex(), i.timeSeconds()));
            }
        }
        return new EngineRoute("graphhopper", path.points(), timings, path.distanceMeters(), path.timeSeconds(),
                List.of(path.points().size() - 1), path.millis());
    }

    /** Which engines answer now (Developer and the route pages show it). */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("url", connections.valhallaUrl());
        v.put("role", "first choice");
        try {
            ValhallaClient.Status s = valhalla.status(connections.valhallaUrl());
            v.put("up", true);
            v.put("version", s.version());
            v.put("millis", s.millis());
        } catch (Exception problem) {
            v.put("up", false);
            v.put("problem", problem.getMessage());
        }
        out.put("valhalla", v);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("url", graphHopperUrl.isEmpty() ? null : graphHopperUrl);
        g.put("role", "fallback");
        if (graphHopperUrl.isEmpty()) {
            g.put("up", false);
            g.put("problem", "not configured (gps.routing.graphhopper-url)");
        } else {
            try {
                GraphHopperClient.Info info = graphHopper.info(graphHopperUrl);
                g.put("up", true);
                g.put("profiles", info.profiles());
                g.put("dataDate", info.dataDate());
                g.put("millis", info.millis());
            } catch (Exception problem) {
                g.put("up", false);
                g.put("problem", problem.getMessage());
            }
        }
        out.put("graphhopper", g);
        out.put("costing", costing);
        return out;
    }

    public String costing() {
        return costing;
    }
}
