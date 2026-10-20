package com.srm.gps.application.storage;

import com.srm.gps.application.masterdata.DeviceVehicleDirectory;
import com.srm.gps.application.masterdata.DeviceVehicleDirectory.VehicleOfDevice;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.database.entity.GpsPosition;
import com.srm.gps.connectivity.database.repository.GpsPositionRepository;
import com.srm.gps.connectivity.kafka.ProcessedGpsPositionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Stores every processed position into gps_tracking.gps_position.
 * Kafka flow: called by consumer group "gps-position-storage-service". Direct flow: called directly.
 *
 * Before storing, it answers "which vehicle and which company does this reading belong to?"
 * using the device_assignment that was valid AT THE TIME OF THE READING ({@link DeviceVehicleDirectory}).
 */
@Service
public class GpsPositionStorageService implements ProcessedGpsPositionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GpsPositionStorageService.class);

    private final GpsPositionRepository gpsPositionRepository;
    private final DeviceVehicleDirectory deviceVehicleDirectory;

    public GpsPositionStorageService(GpsPositionRepository gpsPositionRepository,
                                     DeviceVehicleDirectory deviceVehicleDirectory) {
        this.gpsPositionRepository = gpsPositionRepository;
        this.deviceVehicleDirectory = deviceVehicleDirectory;
    }

    @Override
    @Transactional
    public void handle(ProcessedGpsPosition position) {
        // Kafka may deliver the same message twice (and a device may resend); store each reading once.
        if (gpsPositionRepository.existsByImeiAndRecordedAt(position.imei(), position.recordedAt())) {
            LOG.debug("Skipping already stored reading of device {} at {}", position.imei(), position.recordedAt());
            return;
        }

        Optional<VehicleOfDevice> vehicle = deviceVehicleDirectory.findVehicleAt(position.imei(), position.recordedAt());
        if (vehicle.isEmpty()) {
            LOG.warn("Device {} was not fitted in any vehicle at {} - storing the position without vehicle and tenant",
                    position.imei(), position.recordedAt());
        }

        gpsPositionRepository.save(new GpsPosition(
                position.imei(),
                vehicle.map(VehicleOfDevice::tenantCode).orElse(null),
                vehicle.map(VehicleOfDevice::vehicleRegistrationNo).orElse(null),
                position.recordedAt(),
                position.latitude(),
                position.longitude(),
                position.speedKmph(),
                position.movementStatus(),
                position.distanceFromPreviousMeters(),
                position.secondsSincePreviousReading(),
                position.arrivedOutOfOrder(),
                position.processedAt())
                .withDistanceDetails(position.totalDistanceMeters(),
                        position.distanceSource() == null ? null : position.distanceSource().name(),
                        position.ignition(), position.odometerMeters()));
    }
}
