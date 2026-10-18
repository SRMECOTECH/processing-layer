package com.srm.gps.application.livelocation;

import com.srm.gps.application.masterdata.DeviceVehicleDirectory;
import com.srm.gps.application.masterdata.DeviceVehicleDirectory.VehicleOfDevice;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.kafka.LiveLocationHandler;
import com.srm.gps.connectivity.redis.LiveVehiclePosition;
import com.srm.gps.connectivity.redis.LiveVehiclePositionStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Keeps the LATEST position of every vehicle in Redis - the "where is truck X right now?" answer,
 * without touching the big gps_position table.
 * Kafka flow: consumer group "gps-live-location-service". Direct flow: called directly.
 *
 * An older reading (arrived late, or replayed) never replaces a newer one.
 */
@Service
public class LiveLocationService implements LiveLocationHandler {

    private final LiveVehiclePositionStore liveVehiclePositionStore;
    private final DeviceVehicleDirectory deviceVehicleDirectory;

    public LiveLocationService(LiveVehiclePositionStore liveVehiclePositionStore,
                               DeviceVehicleDirectory deviceVehicleDirectory) {
        this.liveVehiclePositionStore = liveVehiclePositionStore;
        this.deviceVehicleDirectory = deviceVehicleDirectory;
    }

    @Override
    public void handle(ProcessedGpsPosition position) {
        if (position.arrivedOutOfOrder()) {
            return;
        }
        Optional<Instant> newestKnown = liveVehiclePositionStore.findRecordedAt(position.imei());
        if (newestKnown.isPresent() && !position.recordedAt().isAfter(newestKnown.get())) {
            return;
        }

        Optional<VehicleOfDevice> vehicle = deviceVehicleDirectory.findVehicleAt(position.imei(), position.recordedAt());
        liveVehiclePositionStore.save(new LiveVehiclePosition(
                position.imei(),
                vehicle.map(VehicleOfDevice::vehicleRegistrationNo).orElse(null),
                vehicle.map(VehicleOfDevice::tenantCode).orElse(null),
                position.latitude(),
                position.longitude(),
                position.speedKmph(),
                position.movementStatus().name(),
                position.recordedAt(),
                Instant.now()));
    }
}
