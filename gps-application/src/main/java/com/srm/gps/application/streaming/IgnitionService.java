package com.srm.gps.application.streaming;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.IgnitionHandler;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.ignition.IgnitionFinding;
import com.srm.gps.processing.ignition.IgnitionProcessor;
import com.srm.gps.processing.ignition.IgnitionReading;
import com.srm.gps.processing.ignition.IgnitionSettings;
import com.srm.gps.processing.ignition.IgnitionState;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer group gps-ignition-service (Kafka flow) or called directly (direct flow).
 * Ignition (device wire, or virtual from motion), engine seconds and idling, state in Redis gps:state:ignition:{imei}.
 * The stoppage consumer reads this state when a frame carries no ignition of its own.
 */
@Service
public class IgnitionService implements IgnitionHandler {

    private final RedisStateStore redis;
    private final VehicleContext vehicles;
    private final VehicleEventRecorder recorder;
    private final IgnitionSettings settings;

    public IgnitionService(RedisStateStore redis, VehicleContext vehicles, VehicleEventRecorder recorder, StreamProperties properties) {
        this.redis = redis;
        this.vehicles = vehicles;
        this.recorder = recorder;
        this.settings = properties.ignitionSettings();
    }

    @Override
    public void handle(ProcessedGpsPosition position) {
        String key = RedisKeyNames.ignitionState(position.imei());
        IgnitionState state = StateCodecs.ignition(redis.readHash(key));
        IgnitionProcessor.Update update = IgnitionProcessor.update(state, new IgnitionReading(position.recordedAt(),
                position.latitude(), position.longitude(), position.speedKmph(), position.distanceFromPreviousMeters(),
                position.ignition(), position.arrivedOutOfOrder()), settings);
        if (update.state() == state) {
            return;
        }
        if (!update.findings().isEmpty()) {
            VehicleContext.Vehicle vehicle = vehicles.of(position);
            for (IgnitionFinding finding : update.findings()) {
                recorder.record(toEvent(vehicle, finding, update.state()));
            }
        }
        redis.writeHash(key, StateCodecs.ignition(update.state()));
    }

    private static VehicleEvent toEvent(VehicleContext.Vehicle vehicle, IgnitionFinding f, IgnitionState state) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", f.source());
        return switch (f.kind()) {
            case IGNITION_ON, IGNITION_OFF -> {
                details.put(f.kind() == IgnitionFinding.Kind.IGNITION_ON ? "offForSeconds" : "onForSeconds", f.previousSeconds());
                details.put("engineSeconds", state.engineSeconds());
                yield vehicle.event(f.kind() == IgnitionFinding.Kind.IGNITION_ON ? VehicleEventType.IGNITION_ON : VehicleEventType.IGNITION_OFF,
                        EventSeverity.INFO, VehicleEvent.Status.CLOSED, f.startedAt(), f.startedAt(), f.latitude(), f.longitude(),
                        PipelineStage.IGNITION, details);
            }
            case IDLING_STARTED -> {
                details.put("idlingSeconds", f.durationSeconds());
                yield vehicle.event(VehicleEventType.IDLING, EventSeverity.LOW, VehicleEvent.Status.OPEN, f.startedAt(), null,
                        f.latitude(), f.longitude(), PipelineStage.IGNITION, details);
            }
            case IDLING_ENDED -> {
                details.put("idlingSeconds", f.durationSeconds());
                yield vehicle.event(VehicleEventType.IDLING, f.durationSeconds() >= 1800 ? EventSeverity.MEDIUM : EventSeverity.LOW,
                        VehicleEvent.Status.CLOSED, f.startedAt(), f.endedAt(), f.latitude(), f.longitude(), PipelineStage.IGNITION, details);
            }
        };
    }
}
