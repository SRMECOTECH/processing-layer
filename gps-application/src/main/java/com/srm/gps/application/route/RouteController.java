package com.srm.gps.application.route;

import com.srm.gps.application.auth.PortalUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Route plans, live ETA and waypoints (modules route-deviation and waypoints).
 *
 *   GET  /api/routes/engines            Valhalla (HP) and GraphHopper (laptop): which answer, versions
 *   POST /api/routes/preview            {points:[{latitude,longitude}|{placeId}, ...]}: the engine's route, not kept
 *   POST /api/routes                    a plan by hand: {vehicleNo, origin, destination, waypoints[], plannedStartAt}
 *   GET  /api/routes/live?hours=24      one ETA strip per trip (active, and ended within the hours)
 *   GET  /api/routes/{id}               one trip: line, original line, stops, trail on/off the line, events
 *   POST /api/routes/{id}/cancel
 *   GET  /api/routes/log?hours=24       deviations, alternates, wrong direction, arrivals, missed stops
 *   GET  /api/routes/vehicles           vehicles with a device, and their active plan
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    public record Preview(List<RoutePlanService.Point> points) {
    }

    private final RoutePlanService plans;
    private final RouteLiveService live;
    private final RoutingEngines engines;
    private final RoutePlanStore store;

    public RouteController(RoutePlanService plans, RouteLiveService live, RoutingEngines engines, RoutePlanStore store) {
        this.plans = plans;
        this.live = live;
        this.engines = engines;
        this.store = store;
    }

    @GetMapping("/engines")
    public Map<String, Object> engines() {
        return engines.status();
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Preview request) throws IOException {
        return plans.preview(request.points());
    }

    @PostMapping
    public Map<String, Object> create(HttpServletRequest request, @RequestBody RoutePlanService.ManualPlan plan) throws IOException {
        RoutePlanStore.Plan created = plans.create(plan, user(request));
        return live.detail(created.id());
    }

    @GetMapping("/live")
    public Map<String, Object> live(@RequestParam(defaultValue = "24") int hours) {
        return live.live(null, Math.clamp(hours, 1, 24 * 14));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return live.detail(id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        return Map.of("id", id, "cancelled", plans.cancel(id));
    }

    @GetMapping("/log")
    public List<Map<String, Object>> log(@RequestParam(defaultValue = "24") int hours, @RequestParam(defaultValue = "500") int limit) {
        return live.log(Instant.now().minus(Duration.ofHours(Math.clamp(hours, 1, 24 * 31))), null, Math.clamp(limit, 1, 2_000));
    }

    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles() {
        return store.vehiclesWithDevices(null);
    }

    private static String user(HttpServletRequest request) {
        try {
            return PortalUser.of(request).username();
        } catch (RuntimeException notSignedIn) {
            return "console";
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }

    /** The routing engine did not answer (Valhalla off the hotspot and no GraphHopper): 502 with what each said. */
    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> engineDown(IOException problem) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "No routing engine answered: " + problem.getMessage()));
    }
}
