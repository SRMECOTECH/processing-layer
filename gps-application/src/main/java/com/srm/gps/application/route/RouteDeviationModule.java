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
import com.srm.gps.processing.route.RouteTrackState;
import com.srm.gps.processing.route.RouteTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module route-deviation (consumer group gps-route-deviation-service): every truck with a route plan against its plan,
 * live (docs/ROUTE_DEVIATION_ETA_DESIGN.md).
 *
 * Per reading: the truck's plan from {@link RoutePlanDirectory} (none: nothing to do, one map lookup); a re-plan answer
 * waiting for it, if any ({@link RouteTracker#applyCheck}); then the pure {@link RouteTracker}: corridor, progress
 * along the plan, the off-route state machine, wrong direction, pace and ETA. A suspicion that needs the routing engine
 * is queued in {@link RoutePlanService} and never holds the Kafka thread. Events before state, as every module.
 *
 * Events (vehicle_event, one episode per plan: details.episodeScope = plan id):
 *   ROUTE_DEVIATION      MEDIUM / HIGH (> 30 min added) / CRITICAL (> 90 min)  OPEN until it is back on the route
 *   ROUTE_ALTERNATE      INFO   off the line on a road about as good: the re-plan became the plan
 *   WRONG_DIRECTION      HIGH   driving back along the route (or away from the destination), OPEN until forward again
 *   ETA_CHANGED          INFO / MEDIUM (later than promised)  the shown ETA moved 30 min
 *   DESTINATION_REACHED  INFO   promised against actual, deviations, extra km
 */
@Component
public class RouteDeviationModule implements GpsModule {

    private static final Logger LOG = LoggerFactory.getLogger(RouteDeviationModule.class);
    private static final int MAX_REMEMBERED_DEVICES = 200_000;
    /** The plan's live numbers in PostgreSQL at least this often (reading time), and on every status change. */
    private static final Duration SAVE_EVERY = Duration.ofMinutes(2);

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor("route-deviation", "Route deviation & ETA",
            "Each planned trip against its road route: off route, alternate roads, wrong direction, live ETA, destination reached.",
            KafkaConsumerGroupNames.ROUTE_DEVIATION, PipelineStage.ROUTE_DEVIATION, 55,
            List.of("ROUTE_DEVIATION", "ROUTE_ALTERNATE", "WRONG_DIRECTION", "ETA_CHANGED", "DESTINATION_REACHED"),
            List.of("gps:state:route-deviation:*"));

    private final RoutePlanDirectory directory;
    private final RoutePlanService plans;
    private final RoutePlanStore store;
    private final RouteSettings settings;
    private final Map<String, RouteTrackState> recent = new ConcurrentHashMap<>();
    private final Map<Long, Instant> savedAt = new ConcurrentHashMap<>();

    public RouteDeviationModule(RoutePlanDirectory directory, RoutePlanService plans, RoutePlanStore store, RouteSettings settings) {
        this.directory = directory;
        this.plans = plans;
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
        String imei = position.imei();
        RoutePlanDirectory.Entry entry = directory.forVehicle(imei, context.vehicle().registrationNo());
        if (entry == null || position.recordedAt().isBefore(entry.row().plannedStartAt().minus(Duration.ofHours(1)))) {
            return;
        }
        RoutePlan plan = entry.plan();
        RouteTrackState state = remembered(position, context);
        if (state != null && state.planId != plan.id()) {
            state = null;
        }
        ModuleContext.VehicleRef vehicle = context.vehicle();

        RouteTracker.Check answer = plans.takeAnswer(imei);
        if (answer != null && state != null) {
            double done = state.lastOnChainage + state.offDriven;
            RouteTracker.Update checked = RouteTracker.applyCheck(state, answer, settings);
            state = checked.state();
            record(vehicle, entry, state, checked.findings(), context);
            if (checked.findings().stream().anyMatch(f -> f.kind() == RouteFinding.Kind.ALTERNATE_TAKEN)) {
                Optional<RoutePlan> next = plans.adoptAlternate(entry, imei, done);
                if (next.isPresent()) {
                    RoutePlanDirectory.Entry fresh = directory.byId(plan.id());
                    entry = fresh != null ? fresh : new RoutePlanDirectory.Entry(next.get(), entry.row());
                    plan = entry.plan();
                }
            }
        }

        RouteTracker.Update u = RouteTracker.update(state, new RouteTracker.Reading(position.recordedAt(), position.latitude(),
                position.longitude(), position.speedKmph(), position.movementStatus() == MovementStatus.MOVING), plan, settings);
        RouteTrackState after = u.state();
        record(vehicle, entry, after, u.findings(), context);
        if (u.checkWanted() && !plans.requestCheck(imei, RouteTracker.checkRequest(after, plan), plan)) {
            after.checkPending = false;
        }
        context.state().write(after.toFields());
        if (recent.size() > MAX_REMEMBERED_DEVICES) {
            recent.clear();
        }
        recent.put(imei, after);

        boolean arrived = u.findings().stream().anyMatch(f -> f.kind() == RouteFinding.Kind.ARRIVED);
        Instant saved = savedAt.get(plan.id());
        if (u.statusChanged() || arrived || saved == null || Duration.between(saved, position.recordedAt()).compareTo(SAVE_EVERY) >= 0) {
            store.saveLive(plan.id(), after);
            savedAt.put(plan.id(), position.recordedAt());
        }
        if (arrived) {
            store.arrived(plan.id(), after.arrivedAt);
            savedAt.remove(plan.id());
            directory.reload();
        }
    }

    @Override
    public void beforeReplay(Instant from) {
        recent.clear();
        savedAt.clear();
        int reset = store.resetSince(from);
        directory.reload();
        LOG.warn("Route deviation replay from {}: {} plans set back to waiting, to be followed again", from, reset);
    }

    @EventListener
    public void onTelemetryCleared(TelemetryCleared cleared) {
        recent.clear();
        savedAt.clear();
        directory.reload();
    }

    /** The state to start from: the copy in memory when it is the device's previous reading, else Redis. */
    private RouteTrackState remembered(ProcessedGpsPosition position, ModuleContext context) {
        RouteTrackState kept = recent.get(position.imei());
        if (kept != null && kept.lastAt != null) {
            if (!position.recordedAt().isAfter(kept.lastAt)) {
                return kept;
            }
            long seconds = position.secondsSincePreviousReading();
            if (seconds > 0 && Math.abs(Duration.between(kept.lastAt, position.recordedAt().minusSeconds(seconds)).toMillis()) < 1_500) {
                return kept;
            }
        }
        return RouteTrackState.fromFields(context.state().read());
    }

    private void record(ModuleContext.VehicleRef vehicle, RoutePlanDirectory.Entry entry, RouteTrackState state, List<RouteFinding> found,
                        ModuleContext context) {
        for (RouteFinding f : found) {
            switch (f.kind()) {
                case DEVIATION_OPENED, DEVIATION_UPDATED -> context.emit(event(vehicle, entry, f, VehicleEventType.ROUTE_DEVIATION,
                        severity(f), VehicleEvent.Status.OPEN, null));
                case DEVIATION_CLOSED -> context.emit(event(vehicle, entry, f, VehicleEventType.ROUTE_DEVIATION, severity(f),
                        VehicleEvent.Status.CLOSED, f.at()));
                case ALTERNATE_TAKEN -> context.emit(event(vehicle, entry, f, VehicleEventType.ROUTE_ALTERNATE, EventSeverity.INFO,
                        VehicleEvent.Status.CLOSED, f.at()));
                case WRONG_WAY_OPENED -> context.emit(event(vehicle, entry, f, VehicleEventType.WRONG_DIRECTION, EventSeverity.HIGH,
                        VehicleEvent.Status.OPEN, null));
                case WRONG_WAY_CLOSED -> context.emit(event(vehicle, entry, f, VehicleEventType.WRONG_DIRECTION, EventSeverity.HIGH,
                        VehicleEvent.Status.CLOSED, f.at()));
                case ETA_CHANGED -> {
                    Object late = f.details().get("lateMinutes");
                    EventSeverity severity = late instanceof Number n && n.longValue() >= 30 ? EventSeverity.MEDIUM : EventSeverity.INFO;
                    context.emit(event(vehicle, entry, f, VehicleEventType.ETA_CHANGED, severity, VehicleEvent.Status.CLOSED, f.at()));
                }
                case ARRIVED -> context.emit(event(vehicle, entry, f, VehicleEventType.DESTINATION_REACHED, EventSeverity.INFO,
                        VehicleEvent.Status.CLOSED, f.at()));
                default -> {
                }
            }
        }
    }

    private EventSeverity severity(RouteFinding f) {
        Object added = f.details().get("addedMinutes");
        long seconds = added instanceof Number n ? n.longValue() * 60 : 0;
        return EventSeverity.valueOf(RouteTracker.severity(seconds, (String) f.details().get("verdict"), settings));
    }

    private static VehicleEvent event(ModuleContext.VehicleRef vehicle, RoutePlanDirectory.Entry entry, RouteFinding f, VehicleEventType type,
                                      EventSeverity severity, VehicleEvent.Status status, Instant endedAt) {
        RoutePlanStore.Plan row = entry.row();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(VehicleEvent.EPISODE_SCOPE, row.id());
        details.put("planId", row.id());
        details.put("planVersion", entry.plan().version());
        if (row.shipmentId() != null) {
            details.put("shipmentId", row.shipmentId());
            details.put("tripNo", row.tripNo());
        }
        details.put("origin", row.originName());
        details.put("destination", row.destinationName());
        details.putAll(f.details());
        ModuleContext.VehicleRef truck = vehicle.mapped() ? vehicle : new ModuleContext.VehicleRef(vehicle.imei(), row.vehicleNo(), row.tenantCode());
        return truck.event(type, severity, status, f.startedAt(), endedAt, f.latitude(), f.longitude(), PipelineStage.ROUTE_DEVIATION.name(),
                details);
    }
}
