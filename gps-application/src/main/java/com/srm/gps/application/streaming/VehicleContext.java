package com.srm.gps.application.streaming;

import com.srm.gps.application.masterdata.DeviceVehicleDirectory;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Resolves which vehicle and company a reading belongs to, and builds events for it. */
@Component
public class VehicleContext {

    private final DeviceVehicleDirectory directory;

    public VehicleContext(DeviceVehicleDirectory directory) {
        this.directory = directory;
    }

    public record Vehicle(String imei, String registrationNo, String tenantCode) {

        public VehicleEvent event(VehicleEventType type, EventSeverity severity, VehicleEvent.Status status, Instant startedAt,
                                  Instant endedAt, double latitude, double longitude, PipelineStage detectedBy,
                                  Map<String, Object> details) {
            return new VehicleEvent(imei, registrationNo, tenantCode, type, severity, status, startedAt, endedAt, latitude,
                    longitude, detectedBy.name(), details, Instant.now());
        }
    }

    public Vehicle of(ProcessedGpsPosition position) {
        return directory.findVehicleAt(position.imei(), position.recordedAt())
                .map(v -> new Vehicle(position.imei(), v.vehicleRegistrationNo(), v.tenantCode()))
                .orElseGet(() -> new Vehicle(position.imei(), null, null));
    }
}
