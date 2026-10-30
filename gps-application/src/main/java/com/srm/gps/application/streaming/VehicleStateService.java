package com.srm.gps.application.streaming;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.state.VehicleReading;
import com.srm.gps.processing.state.VehicleState;
import com.srm.gps.processing.state.VehicleStateProcessor;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleContext;
import com.srm.gps.module.ModuleDescriptor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module (gps-module-api), consumer group gps-vehicle-state-service (Kafka flow) or called directly (direct flow).
 *
 * Keeps two things per vehicle in Redis:
 *   gps:state:vehicle:{imei}  the vehicle's full state ({@link VehicleStateProcessor}: heading, acceleration,
 *                             Kalman-smoothed track and distance, data-quality counters, time moving/standing);
 *   gps:history:{imei}        its recent readings as a capped Redis Stream - the historical context other
 *                             consumers and the tracking window read (XREVRANGE), trimmed on every write.
 * Kafka keeps the full history for replay (retention 7 days); PostgreSQL keeps every position for good.
 */
@Service
public class VehicleStateService implements GpsModule {

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor("vehicle-state", "Vehicle state",
            "Each vehicle's state and history: heading, acceleration, harsh driving, Kalman-smoothed distance.",
            KafkaConsumerGroupNames.VEHICLE_STATE, PipelineStage.VEHICLE_STATE, 20,
            List.of("HARSH_ACCELERATION", "HARSH_BRAKING"), List.of("gps:state:vehicle:*", "gps:history:*"));

    private final RedisStateStore redis;
    private final VehicleContext vehicles;
    private final VehicleEventRecorder recorder;
    private final VehicleStateProcessor.Settings settings;
    private final long historyLength;

    public VehicleStateService(RedisStateStore redis, VehicleContext vehicles, VehicleEventRecorder recorder, StreamProperties properties) {
        this.redis = redis;
        this.vehicles = vehicles;
        this.recorder = recorder;
        this.settings = properties.vehicleStateSettings();
        this.historyLength = properties.historyLengthOrDefault();
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
        String key = RedisKeyNames.vehicleState(position.imei());
        VehicleState previous = StateCodecs.vehicle(redis.readHash(key));
        if (previous != null && !position.arrivedOutOfOrder() && !position.recordedAt().isAfter(previous.lastRecordedAt())) {
            return;   // a redelivery of a reading already applied
        }

        VehicleStateProcessor.Update update = VehicleStateProcessor.update(previous, new VehicleReading(
                position.imei(), vehicle.registrationNo(), vehicle.tenantCode(), position.recordedAt(), position.latitude(),
                position.longitude(), position.speedKmph(), position.movementStatus() == MovementStatus.MOVING,
                position.ignition(), position.distanceFromPreviousMeters(), position.distanceSourceOrGps(),
                position.totalDistanceMeters(), position.odometerMeters(), position.secondsSincePreviousReading(),
                position.arrivedOutOfOrder()), settings);

        for (VehicleStateProcessor.Finding finding : update.findings()) {
            recorder.record(vehicle.event(
                    finding.kind() == VehicleStateProcessor.Finding.Kind.HARSH_ACCELERATION
                            ? VehicleEventType.HARSH_ACCELERATION : VehicleEventType.HARSH_BRAKING,
                    EventSeverity.MEDIUM, VehicleEvent.Status.CLOSED, finding.recordedAt(), finding.recordedAt(),
                    finding.latitude(), finding.longitude(), PipelineStage.VEHICLE_STATE, finding.details()));
        }
        redis.writeHash(key, StateCodecs.vehicle(update.state()));
        if (!position.arrivedOutOfOrder()) {
            redis.appendHistory(position.imei(), historyEntry(position, update.state()), historyLength);
        }
    }

    private static Map<String, String> historyEntry(ProcessedGpsPosition p, VehicleState s) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("recordedAt", p.recordedAt().toString());
        entry.put("latitude", String.valueOf(p.latitude()));
        entry.put("longitude", String.valueOf(p.longitude()));
        entry.put("speedKmph", String.valueOf(p.speedKmph()));
        entry.put("movement", p.movementStatus().name());
        entry.put("ignition", p.ignition() == null ? "" : p.ignition().toString());
        entry.put("distanceMeters", String.valueOf(p.distanceFromPreviousMeters()));
        entry.put("distanceSource", p.distanceSourceOrGps().name());
        entry.put("secondsSincePrevious", String.valueOf(p.secondsSincePreviousReading()));
        entry.put("impliedSpeedKmph", p.impliedSpeedKmph() == null ? "" : p.impliedSpeedKmph().toString());
        entry.put("totalDistanceMeters", String.valueOf(s.totalDistanceMeters()));
        entry.put("kalmanLatitude", String.valueOf(s.kalmanLatitude()));
        entry.put("kalmanLongitude", String.valueOf(s.kalmanLongitude()));
        entry.put("kalmanSpeedKmph", String.valueOf(s.kalmanSpeedKmph()));
        entry.put("kalmanDistanceMeters", String.valueOf(s.kalmanDistanceMeters()));
        entry.put("headingDegrees", s.headingDegrees() == null ? "" : s.headingDegrees().toString());
        return entry;
    }
}
