package com.srm.gps.application.streaming;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.connectivity.kafka.StreamConsumerHandlers.StoppageHandler;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.ignition.IgnitionState;
import com.srm.gps.processing.stoppage.StopFinding;
import com.srm.gps.processing.stoppage.StoppageDetector;
import com.srm.gps.processing.stoppage.StoppageReading;
import com.srm.gps.processing.stoppage.StoppageSettings;
import com.srm.gps.processing.stoppage.StoppageState;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumer group gps-stoppage-service (Kafka flow) or called directly (direct flow).
 *
 * Ignition for each reading: the frame's own ignition wire when it has one; otherwise the ignition consumer's
 * (virtual) ignition from Redis gps:state:ignition:{imei}; otherwise unknown.
 * A STOPPAGE (engine on) is an alarm and is sent to the {@link StoppageContextAgents} for a traffic/weather verdict;
 * a PARKED stop (engine off) is recorded as INFO, marked neutralised.
 */
@Service
public class StoppageService implements StoppageHandler {

    private final RedisStateStore redis;
    private final VehicleContext vehicles;
    private final VehicleEventRecorder recorder;
    private final StoppageContextAgents agents;
    private final StoppageSettings settings;

    public StoppageService(RedisStateStore redis, VehicleContext vehicles, VehicleEventRecorder recorder,
                           StoppageContextAgents agents, StreamProperties properties) {
        this.redis = redis;
        this.vehicles = vehicles;
        this.recorder = recorder;
        this.agents = agents;
        this.settings = properties.stoppageSettings();
    }

    @Override
    public void handle(ProcessedGpsPosition position) {
        String key = RedisKeyNames.stoppageState(position.imei());
        StoppageState state = StateCodecs.stoppage(redis.readHash(key));

        Boolean ignition = position.ignition();
        String ignitionSource = ignition != null ? IgnitionState.DEVICE : null;
        if (ignition == null) {
            IgnitionState virtual = StateCodecs.ignition(redis.readHash(RedisKeyNames.ignitionState(position.imei())));
            ignition = virtual.ignitionOn();
            ignitionSource = virtual.ignitionOn() == null ? null : virtual.source();
        }

        StoppageDetector.Update update = StoppageDetector.update(state, new StoppageReading(position.recordedAt(),
                position.latitude(), position.longitude(), position.speedKmph(), ignition, ignitionSource,
                position.arrivedOutOfOrder()), settings);
        if (update.state() == state) {
            return;
        }
        if (!update.findings().isEmpty()) {
            VehicleContext.Vehicle vehicle = vehicles.of(position);
            for (StopFinding finding : update.findings()) {
                VehicleEvent event = toEvent(vehicle, finding);
                recorder.record(event);
                if (finding.started() && finding.kind() == StoppageState.Kind.STOPPAGE && !finding.merged()) {
                    agents.investigate(event);
                }
            }
        }
        redis.writeHash(key, StateCodecs.stoppage(update.state()));
    }

    private static VehicleEvent toEvent(VehicleContext.Vehicle vehicle, StopFinding f) {
        boolean parked = f.kind() == StoppageState.Kind.PARKED;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", f.kind().name());
        details.put("neutralised", parked);
        details.put("ignition", f.ignition() == null ? "UNKNOWN" : f.ignition() ? "ON" : "OFF");
        if (f.ignitionSource() != null) {
            details.put("ignitionSource", f.ignitionSource());
        }
        details.put("durationSeconds", f.durationSeconds());
        details.put("readings", f.readings());
        if (!parked) {
            details.put("engineOnSeconds", f.engineOnSeconds());
        }
        if (f.merged()) {
            details.put("mergedWithPreviousStop", true);
        }
        if (f.started() && !parked) {
            details.put("contextVerdict", "PENDING");
        }
        return vehicle.event(parked ? VehicleEventType.PARKED : VehicleEventType.STOPPAGE,
                parked ? EventSeverity.INFO : EventSeverity.MEDIUM,
                f.started() ? VehicleEvent.Status.OPEN : VehicleEvent.Status.CLOSED,
                f.startedAt(), f.endedAt(), f.latitude(), f.longitude(), PipelineStage.STOPPAGE_DETECTION, details);
    }
}
