package com.srm.gps.application.route;

import com.srm.gps.application.geofence.GeofenceDirectory;
import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.route.Polyline6;
import com.srm.gps.processing.route.RoutePlan;
import com.srm.gps.processing.route.RouteSettings;
import com.srm.gps.processing.route.RouteTracker;
import com.srm.gps.processing.rule.DistanceCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Makes and changes route plans, and runs the routing engine for the modules so a Kafka thread never waits on it.
 *
 *  - Every 30 s: each open shipment with an origin and a destination geofence and no plan gets one (origin centre ->
 *    destination centre, costing truck), with the geofences its line crosses as FENCE stops and the lane's pace.
 *    A shipment the engine cannot plan is tried again 10 minutes later.
 *  - By hand: a vehicle, a start, a destination and stops in between (MANUAL stops), replacing its active plan.
 *  - Re-plan checks for the route-deviation module: queued on two threads; the answer waits in {@link #takeAnswer}
 *    for the truck's next reading, and the engine's new line too, in case it becomes the plan (ROUTE_ALTERNATE).
 */
@Service
public class RoutePlanService {

    private static final Logger LOG = LoggerFactory.getLogger(RoutePlanService.class);
    private static final int MAX_FENCE_STOPS = 40;
    private static final int PREVIEW_POINTS = 2_000;

    /** A point of a manual plan: a geofence (placeId) or a position. */
    public record Point(Double latitude, Double longitude, String name, Long placeId, Double radiusMeters, Integer stayMinutes) {
    }

    public record ManualPlan(String vehicleNo, Point origin, Point destination, List<Point> waypoints, Instant plannedStartAt) {
    }

    private final RoutingEngines engines;
    private final RoutePlanStore store;
    private final RoutePlanDirectory directory;
    private final GeofenceDirectory fences;
    private final RouteSettings settings;
    private final boolean planShipments;
    private final ExecutorService checker = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200), r -> {
        Thread t = new Thread(r, "route-check");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RouteTracker.Check> answers = new ConcurrentHashMap<>();
    private final Map<String, RoutingEngines.EngineRoute> replans = new ConcurrentHashMap<>();
    private final Map<Long, Instant> retryAfter = new ConcurrentHashMap<>();
    private volatile String lastPlanningProblem;

    public RoutePlanService(RoutingEngines engines, RoutePlanStore store, RoutePlanDirectory directory, GeofenceDirectory fences,
                            RouteSettings settings,
                            @Value("${gps.modules.route-deviation.enabled:true}") boolean deviationEnabled,
                            @Value("${gps.modules.waypoints.enabled:true}") boolean waypointsEnabled,
                            @Value("${gps.routing.plan-shipments:true}") boolean planShipments) {
        this.engines = engines;
        this.store = store;
        this.directory = directory;
        this.fences = fences;
        this.settings = settings;
        this.planShipments = planShipments && (deviationEnabled || waypointsEnabled);
    }

    @PreDestroy
    void stop() {
        checker.shutdownNow();
    }

    // ---------------------------------------------------------------- plans for shipments

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "${gps.routing.plan-every:PT30S}")
    public void planShipments() {
        if (!planShipments) {
            return;
        }
        try {
            boolean changed = store.closeFinishedShipments() > 0;
            for (RoutePlanStore.ShipmentToPlan s : store.shipmentsToPlan(20)) {
                Instant retry = retryAfter.get(s.id());
                if (retry != null && Instant.now().isBefore(retry)) {
                    continue;
                }
                try {
                    planShipment(s);
                    retryAfter.remove(s.id());
                    changed = true;
                } catch (IOException | RuntimeException problem) {
                    retryAfter.put(s.id(), Instant.now().plus(Duration.ofMinutes(10)));
                    if (!String.valueOf(problem.getMessage()).equals(lastPlanningProblem)) {
                        LOG.warn("Shipment {} ({}) not planned, trying again in 10 min: {}", s.tripNo(), s.vehicleNo(), problem.getMessage());
                        lastPlanningProblem = String.valueOf(problem.getMessage());
                    }
                }
            }
            if (changed) {
                directory.reload();
            }
        } catch (RuntimeException problem) {
            LOG.debug("Shipment planning skipped: {}", problem.getMessage());
        }
    }

    long planShipment(RoutePlanStore.ShipmentToPlan s) throws IOException {
        Map<Long, Geofence> byId = fences.byId();
        Geofence origin = byId.get(s.originPlaceId());
        Geofence destination = byId.get(s.destinationPlaceId());
        if (origin == null || destination == null) {
            throw new IOException("its origin or destination geofence is not active");
        }
        if (DistanceCalculator.distance(origin.latitude(), origin.longitude(), destination.latitude(), destination.longitude())
                < origin.radiusMeters() + destination.radiusMeters()) {
            retryAfter.put(s.id(), Instant.now().plus(Duration.ofDays(30)));
            throw new IOException("origin and destination overlap: nothing to route");
        }
        RoutingEngines.EngineRoute route = engines.route(List.of(new double[] {origin.latitude(), origin.longitude()},
                new double[] {destination.latitude(), destination.longitude()}), Double.NaN);
        List<RoutePlan.Stop> stops = fenceStopsAlong(route.points(), Set.of(origin.id(), destination.id()), 1);
        double pace = store.lanePace(origin.id(), destination.id()).orElse(settings.initialPace);
        long id = store.insert(new RoutePlanStore.NewPlan(s.id(), s.imei(), s.vehicleNo(), s.tenantCode(), "SHIPMENT", origin.name(),
                origin.id(), origin.latitude(), origin.longitude(), destination.name(), destination.id(), destination.latitude(),
                destination.longitude(), destination.radiusMeters(), route.engine(), engines.costing(), "DISPATCH",
                Polyline6.encode(route.points()), route.timings(), route.lengthMeters(), route.seconds(), clampPace(pace),
                s.dispatchedAt(), "shipment " + s.tripNo()), stops);
        store.closeOthers(s.vehicleNo(), s.imei(), id);
        LOG.info("Planned shipment {} ({}): {} -> {}, {} km, {} min by {} ({} stops on the way)", s.tripNo(), s.vehicleNo(),
                origin.name(), destination.name(), Math.round(route.lengthMeters() / 1000), Math.round(route.seconds() / 60),
                route.engine(), stops.size());
        return id;
    }

    // ---------------------------------------------------------------- plans by hand

    public RoutePlanStore.Plan create(ManualPlan req, String user) throws IOException {
        if (req.vehicleNo() == null || req.vehicleNo().isBlank()) {
            throw new IllegalArgumentException("Choose the vehicle");
        }
        Map<Long, Geofence> byId = fences.byId();
        Resolved origin = resolve(req.origin(), "start", byId);
        Resolved destination = resolve(req.destination(), "destination", byId);
        List<Resolved> waypoints = new ArrayList<>();
        for (Point p : req.waypoints() == null ? List.<Point>of() : req.waypoints()) {
            waypoints.add(resolve(p, "stop", byId));
        }
        List<double[]> locations = new ArrayList<>();
        locations.add(new double[] {origin.lat, origin.lon});
        waypoints.forEach(w -> locations.add(new double[] {w.lat, w.lon}));
        locations.add(new double[] {destination.lat, destination.lon});
        RoutingEngines.EngineRoute route = engines.route(locations, Double.NaN);

        List<RoutePlan.Stop> stops = new ArrayList<>();
        int seq = 1;
        for (Resolved w : waypoints) {
            Geofence area = w.fence != null ? w.fence : Geofence.circle(-seq, "STOP-" + seq, w.name, "CHECKPOINT", w.lat, w.lon, w.radius,
                    null, null);
            stops.add(new RoutePlan.Stop(seq++, w.name, "MANUAL", w.fence == null ? null : w.fence.id(), area, -1, w.stay));
        }
        Set<Long> exclude = new HashSet<>();
        if (origin.fence != null) {
            exclude.add(origin.fence.id());
        }
        if (destination.fence != null) {
            exclude.add(destination.fence.id());
        }
        waypoints.stream().filter(w -> w.fence != null).forEach(w -> exclude.add(w.fence.id()));
        stops.addAll(fenceStopsAlong(route.points(), exclude, seq));
        // chainage of each stop on this line, as the modules will see it
        RoutePlan probe = RoutePlan.of(0, 1, route.points(), route.timings(), route.seconds(), stops, null,
                Geofence.circle(0, "D", "D", "DESTINATION", destination.lat, destination.lon, destination.radius, null, null), 0, 1, settings);

        String imei = store.deviceOf(req.vehicleNo()).orElse(null);
        String tenant = store.tenantOf(req.vehicleNo()).orElse(null);
        Long originPlace = origin.fence == null ? null : origin.fence.id();
        Long destinationPlace = destination.fence == null ? null : destination.fence.id();
        double pace = store.lanePace(originPlace, destinationPlace).orElse(settings.initialPace);
        long id = store.insert(new RoutePlanStore.NewPlan(null, imei, req.vehicleNo().trim().toUpperCase(), tenant, "MANUAL", origin.name,
                originPlace, origin.lat, origin.lon, destination.name, destinationPlace, destination.lat, destination.lon,
                destination.radius, route.engine(), engines.costing(), "MANUAL", Polyline6.encode(route.points()), route.timings(),
                route.lengthMeters(), route.seconds(), clampPace(pace),
                req.plannedStartAt() == null ? Instant.now() : req.plannedStartAt(), user), probe.stops());
        store.closeOthers(req.vehicleNo().trim().toUpperCase(), imei, id);
        directory.reload();
        LOG.info("Route planned by {} for {}: {} -> {}, {} km by {}", user, req.vehicleNo(), origin.name, destination.name,
                Math.round(route.lengthMeters() / 1000), route.engine());
        return store.byId(id).orElseThrow();
    }

    /** The engine's route for these points without keeping it: line, length, time, the geofences on the way. */
    public Map<String, Object> preview(List<Point> points) throws IOException {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("A route needs a start and a destination");
        }
        Map<Long, Geofence> byId = fences.byId();
        List<double[]> locations = new ArrayList<>();
        Set<Long> exclude = new HashSet<>();
        int i = 0;
        for (Point p : points) {
            Resolved r = resolve(p, i == 0 ? "start" : i == points.size() - 1 ? "destination" : "stop", byId);
            locations.add(new double[] {r.lat, r.lon});
            if (r.fence != null) {
                exclude.add(r.fence.id());
            }
            i++;
        }
        RoutingEngines.EngineRoute route = engines.route(locations, Double.NaN);
        RoutePlan plan = RoutePlan.of(0, 1, route.points(), route.timings(), route.seconds(),
                fenceStopsAlong(route.points(), exclude, 1), null,
                Geofence.circle(0, "D", "D", "DESTINATION", locations.get(locations.size() - 1)[0],
                        locations.get(locations.size() - 1)[1], settings.arrivalRadiusMeters, null, null), 0, 1, settings);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", route.engine());
        out.put("costing", engines.costing());
        out.put("lengthKm", Math.round(route.lengthMeters() / 100) / 10.0);
        out.put("minutes", Math.round(route.seconds() / 60));
        out.put("millis", route.millis());
        out.put("points", plan.shape(PREVIEW_POINTS));
        List<Map<String, Object>> legs = new ArrayList<>();
        int from = 0;
        for (int end : route.legEnds()) {
            Map<String, Object> leg = new LinkedHashMap<>();
            leg.put("km", Math.round((plan.chainageAt(Math.min(end, plan.size() - 1)) - plan.chainageAt(from)) / 100) / 10.0);
            leg.put("minutes", Math.round((plan.secondsAt(plan.chainageAt(Math.min(end, plan.size() - 1))) - plan.secondsAt(plan.chainageAt(from))) / 60));
            legs.add(leg);
            from = Math.min(end, plan.size() - 1);
        }
        out.put("legs", legs);
        List<Map<String, Object>> onTheWay = new ArrayList<>();
        for (RoutePlan.Stop s : plan.stops()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("placeId", s.placeId());
            f.put("name", s.name());
            f.put("role", s.area().role());
            f.put("km", Math.round(s.chainageMeters() / 100) / 10.0);
            onTheWay.add(f);
        }
        out.put("geofencesOnTheWay", onTheWay);
        return out;
    }

    public boolean cancel(long id) {
        boolean done = store.cancel(id) > 0;
        if (done) {
            directory.reload();
        }
        return done;
    }

    // ---------------------------------------------------------------- re-plan checks (route-deviation module)

    /** Queues a check; false when the queue is full (the module asks again on a later reading). */
    public boolean requestCheck(String imei, RouteTracker.CheckRequest q, RoutePlan plan) {
        try {
            checker.execute(() -> {
                try {
                    List<double[]> locations = new ArrayList<>();
                    locations.add(new double[] {q.latitude(), q.longitude()});
                    q.stopsAhead().forEach(s -> locations.add(new double[] {s.area().latitude(), s.area().longitude()}));
                    Geofence end = plan.destination();
                    locations.add(new double[] {end.latitude(), end.longitude()});
                    RoutingEngines.EngineRoute route = engines.route(locations, q.heading());
                    replans.put(imei, route);
                    answers.put(imei, RouteTracker.judge(q, route.seconds(), route.lengthMeters(), settings));
                } catch (IOException | RuntimeException problem) {
                    LOG.info("Re-plan for {} not available: {}", imei, problem.getMessage());
                    answers.put(imei, RouteTracker.unavailable(q));
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    public RouteTracker.Check takeAnswer(String imei) {
        return answers.remove(imei);
    }

    /**
     * The re-plan of the last check becomes the plan (ROUTE_ALTERNATE): version + 1, starting where the truck was, with
     * the distance already done as its offset; manual stops are kept (placed on the new line), FENCE stops not reached
     * yet are found again along it.
     */
    public Optional<RoutePlan> adoptAlternate(RoutePlanDirectory.Entry entry, String imei, double doneMeters) {
        RoutingEngines.EngineRoute route = replans.remove(imei);
        if (route == null) {
            return Optional.empty();
        }
        RoutePlan old = entry.plan();
        List<RoutePlanStore.Stop> rows = store.stops(old.id());
        List<RoutePlan.Stop> kept = new ArrayList<>();
        Set<Long> exclude = new HashSet<>();
        if (old.origin() != null) {
            exclude.add(old.origin().id());
        }
        exclude.add(old.destination().id());
        int nextSeq = 1;
        for (RoutePlanStore.Stop s : rows) {
            nextSeq = Math.max(nextSeq, s.seq() + 1);
            if ("FENCE".equals(s.kind()) && "PENDING".equals(s.status())) {
                continue;
            }
            if (s.placeId() != null) {
                exclude.add(s.placeId());
            }
            RoutePlan.Stop stop = old.stops().stream().filter(o -> o.seq() == s.seq()).findFirst().orElse(null);
            if (stop != null) {
                // placed again on the new line
                kept.add(new RoutePlan.Stop(stop.seq(), stop.name(), stop.kind(), stop.placeId(), stop.area(), -1, stop.stayMinutes()));
            }
        }
        List<RoutePlan.Stop> found = fenceStopsAlong(route.points(), exclude, nextSeq);
        List<RoutePlan.Stop> all = new ArrayList<>(kept);
        all.addAll(found);
        RoutePlan next = RoutePlan.of(old.id(), old.version() + 1, route.points(), route.timings(), route.seconds(), all, old.origin(),
                old.destination(), old.offsetMeters() + doneMeters, old.initialPace(), settings);
        List<RoutePlan.Stop> placedKept = next.stops().stream().filter(s -> kept.stream().anyMatch(k -> k.seq() == s.seq())).toList();
        List<RoutePlan.Stop> placedNew = next.stops().stream().filter(s -> found.stream().anyMatch(k -> k.seq() == s.seq())).toList();
        store.replaceLine(old.id(), next.version(), Polyline6.encode(route.points()), route.timings(), route.lengthMeters(),
                route.seconds(), next.offsetMeters(), route.engine(), placedKept, placedNew);
        directory.reload();
        return Optional.of(next);
    }

    // ---------------------------------------------------------------- helpers

    /** The geofences the line crosses, in the order it crosses them (at most 40), as FENCE stops without a planned stay. */
    List<RoutePlan.Stop> fenceStopsAlong(List<double[]> points, Set<Long> exclude, int firstSeq) {
        List<RoutePlan.Stop> stops = new ArrayList<>();
        Set<Long> seen = new HashSet<>(exclude);
        var index = fences.index();
        int seq = firstSeq;
        double along = 0;
        for (int i = 0; i < points.size() - 1 && stops.size() < MAX_FENCE_STOPS; i++) {
            double[] a = points.get(i);
            double[] b = points.get(i + 1);
            double length = DistanceCalculator.distance(a[0], a[1], b[0], b[1]);
            for (Geofence f : index.candidatesAlong(a[0], a[1], b[0], b[1])) {
                if (seen.contains(f.id())) {
                    continue;
                }
                double entry = f.contains(a[0], a[1]) ? 0 : f.crossingFraction(a[0], a[1], b[0], b[1]);
                if (entry >= 0) {
                    seen.add(f.id());
                    // placed where the road enters it, not at its centre (a big fence's centre can be far off the road)
                    stops.add(new RoutePlan.Stop(seq++, f.name(), "FENCE", f.id(), f, along + entry * length, 0));
                }
            }
            along += length;
        }
        return stops;
    }

    private record Resolved(double lat, double lon, String name, Geofence fence, double radius, int stay) {
    }

    private Resolved resolve(Point p, String what, Map<Long, Geofence> byId) {
        if (p == null) {
            throw new IllegalArgumentException("Give the " + what);
        }
        int stay = p.stayMinutes() == null ? 0 : Math.max(0, p.stayMinutes());
        if (p.placeId() != null) {
            Geofence f = byId.get(p.placeId());
            if (f == null) {
                throw new IllegalArgumentException("Geofence " + p.placeId() + " (" + what + ") is not active");
            }
            return new Resolved(f.latitude(), f.longitude(), p.name() != null && !p.name().isBlank() ? p.name() : f.name(), f,
                    f.radiusMeters(), stay);
        }
        if (p.latitude() == null || p.longitude() == null) {
            throw new IllegalArgumentException("Give the " + what + " as a geofence or a latitude and longitude");
        }
        double lat = p.latitude();
        double lon = p.longitude();
        if (lat < 6 || lat > 37.5 || lon < 68 || lon > 97.5) {
            throw new IllegalArgumentException("The " + what + " " + lat + ", " + lon + " is not in India (latitude first, then longitude)");
        }
        double radius = p.radiusMeters() != null && p.radiusMeters() > 0 ? p.radiusMeters()
                : "destination".equals(what) ? settings.arrivalRadiusMeters : settings.waypointRadiusMeters;
        String name = p.name() != null && !p.name().isBlank() ? p.name().trim() : String.format("%.5f, %.5f", lat, lon);
        return new Resolved(lat, lon, name, null, radius, stay);
    }

    private double clampPace(double pace) {
        return Math.max(settings.paceMin, Math.min(settings.paceMax, pace));
    }
}
