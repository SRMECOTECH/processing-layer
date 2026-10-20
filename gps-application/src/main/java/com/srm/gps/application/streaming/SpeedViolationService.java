package com.srm.gps.application.streaming;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.SpeedViolationHandler;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.speed.SpeedFinding;
import com.srm.gps.processing.speed.SpeedReading;
import com.srm.gps.processing.speed.SpeedRule;
import com.srm.gps.processing.speed.SpeedViolationProcessor;
import com.srm.gps.processing.speed.SpeedViolationState;
import org.springframework.stereotype.Service;

/**
 * Consumer group gps-speed-violation-service (Kafka flow) or called directly (direct flow).
 *
 * Per reading: rule for the vehicle (Redis, vehicle -> company -> default), state (Redis gps:state:speed:{imei}),
 * the pure {@link SpeedViolationProcessor}, then events, then the new state. Events are written before the state:
 * if anything fails the reading is retried, finds the same events, and the upsert by event key keeps them once.
 */
@Service
public class SpeedViolationService implements SpeedViolationHandler {

    private final RedisStateStore redis;
    private final SpeedRuleStore rules;
    private final VehicleContext vehicles;
    private final VehicleEventRecorder recorder;

    public SpeedViolationService(RedisStateStore redis, SpeedRuleStore rules, VehicleContext vehicles, VehicleEventRecorder recorder) {
        this.redis = redis;
        this.rules = rules;
        this.vehicles = vehicles;
        this.recorder = recorder;
    }

    @Override
    public void handle(ProcessedGpsPosition position) {
        VehicleContext.Vehicle vehicle = vehicles.of(position);
        SpeedRule rule = rules.ruleFor(vehicle.registrationNo(), vehicle.tenantCode());
        String key = RedisKeyNames.speedState(position.imei());
        SpeedViolationState state = StateCodecs.speed(redis.readHash(key));

        SpeedViolationProcessor.Update update = SpeedViolationProcessor.update(state,
                new SpeedReading(position.recordedAt(), position.latitude(), position.longitude(), position.speedKmph(),
                        position.odometerMeters(), position.arrivedOutOfOrder()), rule);
        if (update.state() == state) {
            return;
        }
        for (SpeedFinding finding : update.findings()) {
            recorder.record(toEvent(vehicle, finding));
        }
        redis.writeHash(key, StateCodecs.speed(update.state(), rule));
    }

    private static VehicleEvent toEvent(VehicleContext.Vehicle vehicle, SpeedFinding f) {
        return switch (f.kind()) {
            case VIOLATION_CONFIRMED -> vehicle.event(VehicleEventType.OVERSPEED, severity(f), VehicleEvent.Status.OPEN,
                    f.startedAt(), null, f.latitude(), f.longitude(), PipelineStage.SPEED_VIOLATION, f.details());
            case VIOLATION_ENDED -> vehicle.event(VehicleEventType.OVERSPEED, severity(f), VehicleEvent.Status.CLOSED,
                    f.startedAt(), f.endedAt(), f.latitude(), f.longitude(), PipelineStage.SPEED_VIOLATION, f.details());
            case SENSOR_MISMATCH -> vehicle.event(VehicleEventType.SPEED_SENSOR_MISMATCH, EventSeverity.MEDIUM,
                    VehicleEvent.Status.CLOSED, f.startedAt(), f.endedAt(), f.latitude(), f.longitude(),
                    PipelineStage.SPEED_VIOLATION, withVerdict(f));
        };
    }

    /** By how far over the limit, lowered when the GPS contradicted the speedometer. */
    private static EventSeverity severity(SpeedFinding f) {
        if ("SENSOR_SUSPECT".equals(f.verdict())) {
            return EventSeverity.LOW;
        }
        if (f.excessKmph() >= 30) {
            return EventSeverity.CRITICAL;
        }
        if (f.excessKmph() >= 15) {
            return EventSeverity.HIGH;
        }
        return EventSeverity.MEDIUM;
    }

    private static java.util.Map<String, Object> withVerdict(SpeedFinding f) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>(f.details());
        details.put("verdict", f.verdict());
        return details;
    }
}
