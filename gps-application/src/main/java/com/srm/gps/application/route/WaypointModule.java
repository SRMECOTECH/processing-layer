package com.srm.gps.application.route;

import com.srm.gps.application.admin.TelemetryCleared;
import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleContext;
import com.srm.gps.module.ModuleDescriptor;
import com.srm.gps.processing.route.RouteFinding;
import com.srm.gps.processing.route.RoutePlan;
import com.srm.gps.processing.route.RouteSettings;
import com.srm.gps.processing.route.RouteTracker;
import com.srm.gps.processing.route.WaypointTracker;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module waypoints (consumer group gps-waypoint-service): the planned stops of every trip with a route plan - the
 * waypoints someone planned (MANUAL) and the geofences the planned line crosses (FENCE).
 *
 * Per reading: the truck's plan (none, or no stops: nothing to do); the pure {@link WaypointTracker}; each stop that
 * changed is written to route_stop; then the events and the state (Redis gps:state:waypoints:{imei}).
 *
 * Events (one episode per stop: details.episodeScope = plan:seq):
 *   WAYPOINT_ARRIVED   INFO  2 min inside, stamped at the first reading inside (outOfOrder when an earlier planned stop
 *                            is still to come)
 *   WAYPOINT_DEPARTED  INFO  stamped at the last reading inside, with the stay against the planned stay
 *   WAYPOINT_MISSED    HIGH, planned stops only: 2 km past it, or at the destination (a geofence on the way is only
 *                      marked missed on its stop)
 * A drive-through (in and out within 2 min) is kept on the stop (PASSED) without an event.
 */
@Component
public class WaypointModule implements GpsModule {

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor("waypoints", "Waypoints",
            "The planned stops of each trip: reached, left (with the stay), driven through or missed, and in which order.",
            KafkaConsumerGroupNames.WAYPOINTS, PipelineStage.WAYPOINTS, 52,
            List.of("WAYPOINT_ARRIVED", "WAYPOINT_DEPARTED", "WAYPOINT_MISSED"), List.of("gps:state:waypoints:*"));

    private final RoutePlanDirectory directory;
    private final RoutePlanStore store;
    private final RouteSettings settings;
    private final Map<String, WaypointTracker.State> recent = new ConcurrentHashMap<>();

    public WaypointModule(RoutePlanDirectory directory, RoutePlanStore store, RouteSettings settings) {
        this.directory = directory;
        this.store = store;
        this.settings = settings;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onPosition(ProcessedGpsPosition position, ModuleContext context) {
        if (position.arrivedOutOfOrder()) {
            return;
        }
        RoutePlanDirectory.Entry entry = directory.forVehicle(position.imei(), context.vehicle().registrationNo());
        if (entry == null || entry.plan().stops().isEmpty()
                || position.recordedAt().isBefore(entry.row().plannedStartAt().minus(Duration.ofHours(1)))) {
            return;
        }
        RoutePlan plan = entry.plan();
        WaypointTracker.State before = remembered(position, plan, context);
        WaypointTracker.Update u = WaypointTracker.update(before, new RouteTracker.Reading(position.recordedAt(), position.latitude(),
                position.longitude(), position.speedKmph(), position.movementStatus() == MovementStatus.MOVING), plan, settings);
        if (u.changed()) {
            for (Map.Entry<Integer, WaypointTracker.StopState> stop : u.state().stops().entrySet()) {
                WaypointTracker.StopState was = before == null ? null : before.stops().get(stop.getKey());
                if (was == null || was.status() != stop.getValue().status()) {
                    store.stopChanged(plan.id(), stop.getKey(), stop.getValue());
                }
            }
        }
        ModuleContext.VehicleRef vehicle = context.vehicle();
        for (RouteFinding f : u.findings()) {
            switch (f.kind()) {
                case WAYPOINT_ARRIVED -> context.emit(event(vehicle, entry, f, VehicleEventType.WAYPOINT_ARRIVED, EventSeverity.INFO,
                        VehicleEvent.Status.OPEN, null));
                case WAYPOINT_DEPARTED -> {
                    // the stay closes the arrival's episode; the departure itself is a moment of its own
                    context.emit(event(vehicle, entry, f, VehicleEventType.WAYPOINT_ARRIVED, EventSeverity.INFO, VehicleEvent.Status.CLOSED,
                            f.at()));
                    RouteFinding left = new RouteFinding(f.kind(), f.at(), f.at(), f.latitude(), f.longitude(), f.details());
                    context.emit(event(vehicle, entry, left, VehicleEventType.WAYPOINT_DEPARTED, EventSeverity.INFO,
                            VehicleEvent.Status.CLOSED, f.at()));
                }
                case WAYPOINT_MISSED -> {
                    // only a planned stop is news; a geofence the road happens to cross is just marked missed on the stop
                    if ("MANUAL".equals(f.details().get("stopKind"))) {
                        context.emit(event(vehicle, entry, f, VehicleEventType.WAYPOINT_MISSED, EventSeverity.HIGH, VehicleEvent.Status.CLOSED,
                                f.at()));
                    }
                }
                default -> {
                }
            }
        }
        context.state().write(u.state().toFields());
        if (recent.size() > 200_000) {
            recent.clear();
        }
        recent.put(position.imei(), u.state());
    }

    @Override
    public void beforeReplay(Instant from) {
        recent.clear();
    }

    @EventListener
    public void onTelemetryCleared(TelemetryCleared cleared) {
        recent.clear();
    }

    private WaypointTracker.State remembered(ProcessedGpsPosition position, RoutePlan plan, ModuleContext context) {
        WaypointTracker.State kept = recent.get(position.imei());
        if (kept != null && kept.planId() == plan.id() && kept.lastAt() != null) {
            long seconds = position.secondsSincePreviousReading();
            if (!position.recordedAt().isAfter(kept.lastAt()) || (seconds > 0
                    && Math.abs(Duration.between(kept.lastAt(), position.recordedAt().minusSeconds(seconds)).toMillis()) < 1_500)) {
                return kept;
            }
        }
        return WaypointTracker.State.fromFields(context.state().read(), plan);
    }

    private static VehicleEvent event(ModuleContext.VehicleRef vehicle, RoutePlanDirectory.Entry entry, RouteFinding f, VehicleEventType type,
                                      EventSeverity severity, VehicleEvent.Status status, Instant endedAt) {
        RoutePlanStore.Plan row = entry.row();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(VehicleEvent.EPISODE_SCOPE, row.id() + ":" + f.details().get("seq"));
        details.put("planId", row.id());
        if (row.shipmentId() != null) {
            details.put("shipmentId", row.shipmentId());
            details.put("tripNo", row.tripNo());
        }
        details.put("origin", row.originName());
        details.put("destination", row.destinationName());
        details.putAll(f.details());
        ModuleContext.VehicleRef truck = vehicle.mapped() ? vehicle : new ModuleContext.VehicleRef(vehicle.imei(), row.vehicleNo(), row.tenantCode());
        return truck.event(type, severity, status, f.startedAt(), endedAt, f.latitude(), f.longitude(), PipelineStage.WAYPOINTS.name(), details);
    }
}
