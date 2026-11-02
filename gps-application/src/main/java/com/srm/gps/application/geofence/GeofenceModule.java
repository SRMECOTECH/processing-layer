package com.srm.gps.application.geofence;

import com.srm.gps.application.admin.TelemetryCleared;
import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleContext;
import com.srm.gps.module.ModuleDescriptor;
import com.srm.gps.processing.geofence.GeofenceFinding;
import com.srm.gps.processing.geofence.GeofenceReading;
import com.srm.gps.processing.geofence.GeofenceSettings;
import com.srm.gps.processing.geofence.GeofenceTrackState;
import com.srm.gps.processing.geofence.GeofenceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module geofence (consumer group gps-geofence-service): every vehicle against every active geofence, live.
 *
 * Per reading: the pure {@link GeofenceTracker} with the shared {@link GeofenceDirectory} index, then the visits
 * (geofence_visit) and events, then - only when the fences it remembers changed - the state (Redis
 * gps:state:geofence:{imei}). Events before state, as every module: if anything fails, the reading comes again and the
 * upserts find the same rows.
 *
 * Fast path: a truck on the highway is in an empty grid cell with no fences remembered, so the reading costs one hash
 * lookup and nothing is written. The last reading per device is kept in memory ({@link #recent}); it is trusted only
 * when it is exactly the device's previous reading (the reading says how many seconds ago that was), so another
 * service instance or a restart in between makes the module read Redis again instead of using a stale copy.
 *
 * Events (vehicle_event, Live -> Event stream, the portals' alerts), one per fence (details.episodeScope = fence id):
 *   GEOFENCE_ENTER  INFO     the first reading inside, once the visit held 15 min
 *   GEOFENCE_EXIT   INFO     the last reading inside, with the dwell
 *   GEOFENCE_PASS   INFO     through without stopping (or over it between two readings: inferred)
 *   GEOFENCE_DWELL  HIGH     inside longer than the fence's dwell limit: OPEN until it leaves
 */
@Component
public class GeofenceModule implements GpsModule {

    private static final Logger LOG = LoggerFactory.getLogger(GeofenceModule.class);
    private static final int MAX_REMEMBERED_DEVICES = 200_000;

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor("geofence", "Geofencing",
            "Which geofences every vehicle is inside, live: entries, exits, drive-throughs and over-long stays.",
            KafkaConsumerGroupNames.GEOFENCE, PipelineStage.GEOFENCE, 45,
            List.of("GEOFENCE_ENTER", "GEOFENCE_EXIT", "GEOFENCE_PASS", "GEOFENCE_DWELL"), List.of("gps:state:geofence:*"));

    private final GeofenceDirectory fences;
    private final GeofenceVisitStore visits;
    private final GeofenceSettings settings;
    private final Map<String, GeofenceTrackState> recent = new ConcurrentHashMap<>();

    public GeofenceModule(GeofenceDirectory fences, GeofenceVisitStore visits, Environment environment) {
        this.fences = fences;
        this.visits = visits;
        this.settings = settings(environment);
    }

    /** gps.modules.geofence.* (see application.yml), the smart_truck rules by default. */
    static GeofenceSettings settings(Environment env) {
        GeofenceSettings d = GeofenceSettings.defaults();
        String p = "gps.modules.geofence.";
        return new GeofenceSettings(
                env.getProperty(p + "confirm-minutes", Long.class, d.confirmSeconds() / 60) * 60,
                env.getProperty(p + "escape-factor", Double.class, d.escapeFactor()),
                env.getProperty(p + "pass-min-travel-meters", Double.class, d.passMinTravelMeters()),
                env.getProperty(p + "crossing-max-gap-minutes", Long.class, d.crossingMaxGapSeconds() / 60) * 60,
                env.getProperty(p + "crossing-min-move-meters", Double.class, d.crossingMinMoveMeters()),
                env.getProperty(p + "dwell-alert-minutes", Integer.class, d.defaultDwellAlertMinutes()),
                Map.of("ORIGIN", env.getProperty(p + "dwell-alert-minutes-origin", Integer.class, d.dwellAlertMinutesByRole().get("ORIGIN")),
                        "DESTINATION", env.getProperty(p + "dwell-alert-minutes-destination", Integer.class, d.dwellAlertMinutesByRole().get("DESTINATION")),
                        "YARD", env.getProperty(p + "dwell-alert-minutes-yard", Integer.class, d.dwellAlertMinutesByRole().get("YARD"))));
    }

    public GeofenceSettings settings() {
        return settings;
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
        GeofenceTrackState before = remembered(position, context);
        GeofenceTracker.Update update = GeofenceTracker.update(before, new GeofenceReading(position.recordedAt(), position.latitude(),
                position.longitude(), position.speedKmph(), position.secondsSincePreviousReading(), false), fences.index(), settings);
        if (!update.findings().isEmpty()) {
            ModuleContext.VehicleRef vehicle = context.vehicle();
            for (GeofenceFinding finding : update.findings()) {
                record(vehicle, finding, context);
            }
        }
        if (update.durableChange()) {
            context.state().write(update.state().toFields());
        }
        if (recent.size() > MAX_REMEMBERED_DEVICES) {
            recent.clear();
        }
        recent.put(position.imei(), update.state());
    }

    @Override
    public void beforeReplay(Instant from) {
        recent.clear();
        int rows = visits.cutAt(from);
        LOG.warn("Geofence replay from {}: {} visits removed or cut, to be found again", from, rows);
    }

    /** "Clear database" and the demo reset empty Redis and the visits: forget the copies in memory too. */
    @EventListener
    public void onTelemetryCleared(TelemetryCleared cleared) {
        recent.clear();
    }

    /** The state to start from: the copy in memory when it is the device's previous reading, else Redis. */
    private GeofenceTrackState remembered(ProcessedGpsPosition position, ModuleContext context) {
        GeofenceTrackState kept = recent.get(position.imei());
        if (kept != null && kept.lastRecordedAt() != null) {
            if (!position.recordedAt().isAfter(kept.lastRecordedAt())) {
                return kept;
            }
            long seconds = position.secondsSincePreviousReading();
            if (seconds > 0 && Math.abs(Duration.between(kept.lastRecordedAt(),
                    position.recordedAt().minusSeconds(seconds)).toMillis()) < 1_500) {
                return kept;
            }
        }
        return GeofenceTrackState.fromFields(context.state().read());
    }

    private void record(ModuleContext.VehicleRef vehicle, GeofenceFinding f, ModuleContext context) {
        switch (f.kind()) {
            case ENTERED -> {
                visits.entered(vehicle, f);
                context.emit(event(vehicle, f, VehicleEventType.GEOFENCE_ENTER, EventSeverity.INFO, VehicleEvent.Status.CLOSED,
                        f.at(), f.at(), Map.of("entryGapMinutes", f.gapSeconds() / 60)));
            }
            case EXITED -> {
                visits.exited(vehicle, f);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("enteredAt", f.enteredAt() == null ? null : f.enteredAt().toString());
                extra.put("dwellMinutes", f.dwellSeconds() / 60);
                extra.put("exitGapMinutes", f.gapSeconds() / 60);
                extra.put("reason", f.reason());
                context.emit(event(vehicle, f, VehicleEventType.GEOFENCE_EXIT, EventSeverity.INFO, VehicleEvent.Status.CLOSED,
                        f.at(), f.at(), extra));
                if (f.dwellAlertAt() != null) {
                    context.emit(event(vehicle, f, VehicleEventType.GEOFENCE_DWELL, EventSeverity.HIGH, VehicleEvent.Status.CLOSED,
                            f.dwellAlertAt(), f.at(), Map.of("dwellMinutes", f.dwellSeconds() / 60)));
                }
            }
            case PASSED -> {
                visits.passed(vehicle, f);
                context.emit(event(vehicle, f, VehicleEventType.GEOFENCE_PASS, EventSeverity.INFO, VehicleEvent.Status.CLOSED,
                        f.at(), f.endedAt(), Map.of("inferred", f.inferred(), "minutesInside", f.dwellSeconds() / 60)));
            }
            case DWELL_EXCEEDED -> {
                visits.dwellExceeded(vehicle, f);
                context.emit(event(vehicle, f, VehicleEventType.GEOFENCE_DWELL, EventSeverity.HIGH, VehicleEvent.Status.OPEN,
                        f.at(), null, Map.of("limitMinutes", f.dwellLimitMinutes(),
                                "enteredAt", f.enteredAt().toString())));
            }
        }
    }

    private static VehicleEvent event(ModuleContext.VehicleRef vehicle, GeofenceFinding f, VehicleEventType type, EventSeverity severity,
                                      VehicleEvent.Status status, Instant startedAt, Instant endedAt, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(VehicleEvent.EPISODE_SCOPE, f.fence().id());
        details.put("fenceId", f.fence().id());
        details.put("fenceCode", f.fence().code());
        details.put("fenceName", f.fence().name());
        details.put("fenceRole", f.fence().role());
        details.putAll(extra);
        return vehicle.event(type, severity, status, startedAt, endedAt, f.latitude(), f.longitude(), PipelineStage.GEOFENCE.name(), details);
    }
}
