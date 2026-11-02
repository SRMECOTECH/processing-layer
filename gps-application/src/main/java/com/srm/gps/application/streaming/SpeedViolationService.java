package com.srm.gps.application.streaming;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.application.geofence.GeofenceDirectory;
import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.speed.SpeedFinding;
import com.srm.gps.processing.speed.SpeedPolicy;
import com.srm.gps.processing.speed.SpeedReading;
import com.srm.gps.processing.speed.SpeedRule;
import com.srm.gps.processing.speed.SpeedViolationProcessor;
import com.srm.gps.processing.speed.SpeedViolationState;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleContext;
import com.srm.gps.module.ModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module (gps-module-api), consumer group gps-speed-violation-service (Kafka flow) or called directly (direct flow).
 *
 * Per reading: rule for the vehicle (Redis, vehicle -> company -> default), adjusted by the alert policy to the place
 * (the strictest speed limit of the geofences the reading is inside, e.g. 20 km/h in a plant) and the hour (stricter at
 * night, when switched on); state (Redis gps:state:speed:{imei}); the pure {@link SpeedViolationProcessor}; then
 * events, graded by the policy's bands; then the new state. Events are written before the state: if anything fails the
 * reading is retried, finds the same events, and the upsert by event key keeps them once.
 */
@Service
public class SpeedViolationService implements GpsModule {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedViolationService.class);

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor("speed-violation", "Speed violation",
            "Sustained over-speed against each entity's rule and each geofence's limit, speedometer cross-checked against GPS.",
            KafkaConsumerGroupNames.SPEED_VIOLATION, PipelineStage.SPEED_VIOLATION, 30,
            List.of("OVERSPEED", "SPEED_SENSOR_MISMATCH"), List.of("gps:state:speed:*"));

    private final RedisStateStore redis;
    private final SpeedRuleStore rules;
    private final SpeedPolicyStore policies;
    private final GeofenceDirectory geofences;
    private final VehicleContext vehicles;
    private final VehicleEventRecorder recorder;
    private final ZoneId zone;

    public SpeedViolationService(RedisStateStore redis, SpeedRuleStore rules, SpeedPolicyStore policies, GeofenceDirectory geofences,
                                 VehicleContext vehicles, VehicleEventRecorder recorder,
                                 @Value("${gps.reports.zone:Asia/Kolkata}") String zone) {
        this.redis = redis;
        this.rules = rules;
        this.policies = policies;
        this.geofences = geofences;
        this.vehicles = vehicles;
        this.recorder = recorder;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onPosition(ProcessedGpsPosition position, ModuleContext context) {
        handle(position);
    }

    public void handle(ProcessedGpsPosition position) {
        VehicleContext.Vehicle vehicle = vehicles.of(position);
        SpeedPolicy policy = policies.current();
        Geofence zoneFence = strictestZone(position);
        SpeedRule rule = policy.effective(rules.ruleFor(vehicle.registrationNo(), vehicle.tenantCode()),
                zoneFence == null ? null : zoneFence.speedLimitKmph(), zoneFence == null ? null : zoneFence.code(),
                LocalTime.ofInstant(position.recordedAt(), zone));
        String key = RedisKeyNames.speedState(position.imei());
        SpeedViolationState state = StateCodecs.speed(redis.readHash(key));

        SpeedViolationProcessor.Update update = SpeedViolationProcessor.update(state,
                new SpeedReading(position.recordedAt(), position.latitude(), position.longitude(), position.speedKmph(),
                        position.odometerMeters(), position.arrivedOutOfOrder()), rule);
        if (update.state() == state) {
            return;
        }
        for (SpeedFinding finding : update.findings()) {
            recorder.record(toEvent(vehicle, finding, policy, zoneFence));
        }
        redis.writeHash(key, StateCodecs.speed(update.state(), rule));
    }

    /** The geofence with the lowest speed limit around the reading, or null (no index yet, or none with a limit). */
    private Geofence strictestZone(ProcessedGpsPosition position) {
        try {
            Geofence strictest = null;
            for (Geofence f : geofences.index().locate(position.latitude(), position.longitude())) {
                if (f.speedLimitKmph() != null && (strictest == null || f.speedLimitKmph() < strictest.speedLimitKmph())) {
                    strictest = f;
                }
            }
            return strictest;
        } catch (RuntimeException fencesUnavailable) {
            LOG.debug("Speed check without geofence limits: {}", fencesUnavailable.getMessage());
            return null;
        }
    }

    private static VehicleEvent toEvent(VehicleContext.Vehicle vehicle, SpeedFinding f, SpeedPolicy policy, Geofence zoneFence) {
        return switch (f.kind()) {
            case VIOLATION_CONFIRMED -> vehicle.event(VehicleEventType.OVERSPEED, severity(f, policy), VehicleEvent.Status.OPEN,
                    f.startedAt(), null, f.latitude(), f.longitude(), PipelineStage.SPEED_VIOLATION, graded(f, policy, zoneFence));
            case VIOLATION_ENDED -> vehicle.event(VehicleEventType.OVERSPEED, severity(f, policy), VehicleEvent.Status.CLOSED,
                    f.startedAt(), f.endedAt(), f.latitude(), f.longitude(), PipelineStage.SPEED_VIOLATION, graded(f, policy, zoneFence));
            case SENSOR_MISMATCH -> vehicle.event(VehicleEventType.SPEED_SENSOR_MISMATCH, EventSeverity.MEDIUM,
                    VehicleEvent.Status.CLOSED, f.startedAt(), f.endedAt(), f.latitude(), f.longitude(),
                    PipelineStage.SPEED_VIOLATION, withVerdict(f));
        };
    }

    /** The policy's band: how far over the (adjusted) limit, lowered when the GPS contradicted the speedometer. */
    private static EventSeverity severity(SpeedFinding f, SpeedPolicy policy) {
        return switch (policy.band(f.excessKmph(), f.verdict())) {
            case SUSPECT -> EventSeverity.LOW;
            case SEVERE -> EventSeverity.CRITICAL;
            case BREACH -> EventSeverity.HIGH;
            case WARNING -> EventSeverity.MEDIUM;
        };
    }

    private static Map<String, Object> graded(SpeedFinding f, SpeedPolicy policy, Geofence zoneFence) {
        Map<String, Object> details = new LinkedHashMap<>(f.details());
        SpeedPolicy.Band band = policy.band(f.excessKmph(), f.verdict());
        details.put("band", band.name());
        // a suspect speedometer is a device-health ticket, not a driver alert
        details.put("driverAlert", band != SpeedPolicy.Band.SUSPECT);
        if (zoneFence != null && String.valueOf(details.get("ruleScope")).contains("+zone:")) {
            details.put("zone", zoneFence.code());
            details.put("zoneName", zoneFence.name());
        }
        return details;
    }

    private static Map<String, Object> withVerdict(SpeedFinding f) {
        Map<String, Object> details = new LinkedHashMap<>(f.details());
        details.put("verdict", f.verdict());
        return details;
    }
}
