package com.srm.gps.route.api;

import com.srm.gps.route.engine.GeoPoint;
import com.srm.gps.route.engine.GraphHopperClient;
import com.srm.gps.route.engine.RouteQuery;
import com.srm.gps.route.engine.RouteResult;
import com.srm.gps.route.engine.RoutingAlgorithm;
import com.srm.gps.route.engine.RoutingEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Route planning on the India road map. Points are "latitude,longitude".
 *
 *   GET /api/routing/engine
 *   GET /api/routing/route?from=22.8046,86.2029&to=22.5726,88.3639&profile=truck&algorithm=ASTAR
 *       (optional: via=... repeated, in order; instructions=false)
 *   GET /api/routing/compare?from=22.8046,86.2029&to=23.3441,85.3096&profile=truck
 *       every algorithm on the same trip: same route, different amount of work
 *
 * algorithm: CONTRACTION_HIERARCHIES (default) | LANDMARKS_ASTAR | ASTAR_BIDIRECTIONAL | ASTAR |
 *            DIJKSTRA_BIDIRECTIONAL | DIJKSTRA | ALTERNATIVES
 */
@RestController
public class RoutePlanningController {

    private final GraphHopperClient engine;

    public RoutePlanningController(GraphHopperClient engine) {
        this.engine = engine;
    }

    @GetMapping("/api/routing/engine")
    public Map<String, Object> engine() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("url", engine.url());
        view.put("defaultProfile", engine.defaultProfile());
        try {
            Map<String, Object> info = engine.info();
            view.put("status", "UP");
            view.put("version", info.get("version"));
            view.put("bbox", info.get("bbox"));
            view.put("profiles", info.get("profiles"));
            view.put("dataDate", info.get("data_date"));
            view.put("importDate", info.get("import_date"));
        } catch (RoutingEngineException problem) {
            view.put("status", "DOWN");
            view.put("error", problem.getMessage());
        }
        view.put("algorithms", Arrays.stream(RoutingAlgorithm.values())
                .map(a -> Map.of("name", a.name(), "label", a.label(), "description", a.description()))
                .toList());
        return view;
    }

    @GetMapping("/api/routing/route")
    public RouteView route(@RequestParam String from, @RequestParam String to,
                           @RequestParam(required = false) List<String> via,
                           @RequestParam(required = false) String profile,
                           @RequestParam(required = false) String algorithm,
                           @RequestParam(defaultValue = "true") boolean instructions) {
        RouteQuery query = new RouteQuery(points(from, via, to), profileOrDefault(profile), algorithm(algorithm), instructions);
        return RouteView.from(engine.route(query));
    }

    @GetMapping("/api/routing/compare")
    public List<AlgorithmRunView> compare(@RequestParam String from, @RequestParam String to,
                                          @RequestParam(required = false) String profile) {
        List<GeoPoint> points = points(from, null, to);
        String chosenProfile = profileOrDefault(profile);
        List<AlgorithmRunView> runs = new ArrayList<>();
        for (RoutingAlgorithm algorithm : RoutingAlgorithm.values()) {
            long started = System.nanoTime();
            try {
                RouteResult result = engine.route(new RouteQuery(points, chosenProfile, algorithm, false));
                runs.add(AlgorithmRunView.of(algorithm, result, (System.nanoTime() - started) / 1_000_000));
            } catch (RoutingEngineException.Unavailable down) {
                throw down;
            } catch (RoutingEngineException refused) {
                runs.add(AlgorithmRunView.failed(algorithm, refused.getMessage(), (System.nanoTime() - started) / 1_000_000));
            }
        }
        return runs;
    }

    @ExceptionHandler(RoutingEngineException.Unavailable.class)
    ResponseEntity<Map<String, String>> engineDown(RoutingEngineException.Unavailable problem) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler({RoutingEngineException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }

    private String profileOrDefault(String profile) {
        return profile == null || profile.isBlank() ? engine.defaultProfile() : profile.trim();
    }

    private static List<GeoPoint> points(String from, List<String> via, String to) {
        List<GeoPoint> points = new ArrayList<>();
        points.add(GeoPoint.parse(from));
        if (via != null) {
            via.forEach(point -> points.add(GeoPoint.parse(point)));
        }
        points.add(GeoPoint.parse(to));
        return points;
    }

    static RoutingAlgorithm algorithm(String name) {
        if (name == null || name.isBlank()) {
            return RoutingAlgorithm.CONTRACTION_HIERARCHIES;
        }
        try {
            return RoutingAlgorithm.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown algorithm '" + name + "', expected one of "
                    + Arrays.toString(RoutingAlgorithm.values()));
        }
    }
}
