package com.srm.gps.application.route;

import com.srm.gps.application.geofence.GeofenceDirectory;
import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.route.Polyline6;
import com.srm.gps.processing.route.RoutePlan;
import com.srm.gps.processing.route.RouteSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The active route plans as ready-to-project {@link RoutePlan}s, shared by the route-deviation and waypoints modules:
 * a reading finds its truck's plan with one map lookup (by IMEI, else by vehicle), and nothing when the truck has none.
 *
 * Reloaded at once after a change made here (new plan, re-plan, cancel) and within 30 s after any other change (the
 * fingerprint counts active plans and their versions, so the live numbers written every few minutes do not trigger it).
 * A plan whose id and version did not change is kept as it is, so a reload costs only the new plans.
 */
@Component
public class RoutePlanDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(RoutePlanDirectory.class);

    /** A plan and the row it came from (trip number, places, for the events). */
    public record Entry(RoutePlan plan, RoutePlanStore.Plan row) {
    }

    private final RoutePlanStore store;
    private final GeofenceDirectory fences;
    private final RouteSettings settings;
    private volatile Map<Long, Entry> byId = Map.of();
    private volatile Map<String, Long> byImei = Map.of();
    private volatile Map<String, Long> byVehicle = Map.of();
    private volatile String fingerprint;
    private volatile boolean loaded;

    public RoutePlanDirectory(RoutePlanStore store, GeofenceDirectory fences, RouteSettings settings) {
        this.store = store;
        this.fences = fences;
        this.settings = settings;
    }

    /** The truck's active plan, or null. */
    public Entry forVehicle(String imei, String vehicleNo) {
        if (!loaded) {
            reload();
        }
        Long id = imei == null ? null : byImei.get(imei);
        if (id == null && vehicleNo != null) {
            id = byVehicle.get(vehicleNo);
        }
        return id == null ? null : byId.get(id);
    }

    public Entry byId(long id) {
        if (!loaded) {
            reload();
        }
        return byId.get(id);
    }

    public List<Entry> all() {
        if (!loaded) {
            reload();
        }
        return new ArrayList<>(byId.values());
    }

    public synchronized void reload() {
        try {
            String print = store.fingerprint();
            List<RoutePlanStore.Plan> rows = store.active();
            Map<Long, List<RoutePlanStore.Stop>> stops = store.stops(rows.stream().map(RoutePlanStore.Plan::id).toList());
            Map<Long, Entry> plans = new HashMap<>();
            Map<String, Long> imeis = new HashMap<>();
            Map<String, Long> vehicles = new HashMap<>();
            Map<Long, Entry> before = byId;
            for (RoutePlanStore.Plan row : rows) {
                Entry kept = before.get(row.id());
                Entry entry;
                if (kept != null && kept.plan().version() == row.version()) {
                    entry = new Entry(kept.plan(), row);
                } else {
                    try {
                        entry = new Entry(build(row, stops.getOrDefault(row.id(), List.of())), row);
                    } catch (RuntimeException broken) {
                        LOG.warn("Route plan {} cannot be used: {}", row.id(), broken.getMessage());
                        continue;
                    }
                }
                plans.put(row.id(), entry);
                if (row.imei() != null) {
                    imeis.put(row.imei(), row.id());
                }
                vehicles.put(row.vehicleNo(), row.id());
            }
            byId = plans;
            byImei = imeis;
            byVehicle = vehicles;
            fingerprint = print;
            loaded = true;
        } catch (RuntimeException problem) {
            LOG.warn("Route plans not loaded: {}", problem.getMessage());
            loaded = true;
        }
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    public void reloadIfChanged() {
        try {
            if (!store.fingerprint().equals(fingerprint)) {
                reload();
            }
        } catch (RuntimeException problem) {
            LOG.debug("Route plan fingerprint not read: {}", problem.getMessage());
        }
    }

    /** The plan as the engine sees it: the line, its timings, its stops' areas, the origin and destination areas. */
    public RoutePlan build(RoutePlanStore.Plan row, List<RoutePlanStore.Stop> stopRows) {
        Map<Long, Geofence> fenceById = fences.byId();
        List<RoutePlan.Stop> stops = new ArrayList<>();
        for (RoutePlanStore.Stop s : stopRows) {
            Geofence area = s.placeId() != null && fenceById.containsKey(s.placeId()) ? fenceById.get(s.placeId())
                    : Geofence.circle(-s.id(), "STOP-" + s.seq(), s.name(), "CHECKPOINT", s.latitude(), s.longitude(), s.radius(), null, null);
            stops.add(new RoutePlan.Stop(s.seq(), s.name(), s.kind(), s.placeId(), area, s.chainage(), s.stayMinutes()));
        }
        Geofence origin = row.originPlaceId() != null ? fenceById.get(row.originPlaceId()) : null;
        Geofence destination = row.destinationPlaceId() != null ? fenceById.get(row.destinationPlaceId()) : null;
        if (destination == null) {
            destination = Geofence.circle(-row.id(), "DEST-" + row.id(), row.destinationName() == null ? "Destination" : row.destinationName(),
                    "DESTINATION", row.destinationLat(), row.destinationLon(), row.destinationRadius(), null, null);
        }
        return RoutePlan.of(row.id(), row.version(), Polyline6.decode(row.shape()), RoutePlanStore.timings(row.timings()),
                row.plannedSeconds(), stops, origin, destination, row.offsetMeters(), row.initialPace(), settings);
    }
}
