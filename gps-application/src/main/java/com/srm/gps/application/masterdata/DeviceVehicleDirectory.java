package com.srm.gps.application.masterdata;

import com.srm.gps.connectivity.database.entity.DeviceAssignment;
import com.srm.gps.connectivity.database.entity.Tenant;
import com.srm.gps.connectivity.database.entity.Vehicle;
import com.srm.gps.connectivity.database.repository.DeviceAssignmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "which vehicle (and company) was this device fitted in AT THIS MOMENT?"
 *
 * The answer comes from device_assignment and is cached per device together with its period (from - to),
 * so 150 vehicles reporting every 2 seconds do not cost a database query per message.
 * A reading outside the cached period (the device was moved) simply asks the database again.
 */
@Component
public class DeviceVehicleDirectory {

    /** The vehicle of a device during one assignment period. validTo = null: still fitted. */
    public record VehicleOfDevice(String vehicleRegistrationNo, String tenantCode, Instant validFrom, Instant validTo) {
        boolean covers(Instant moment) {
            return !moment.isBefore(validFrom) && (validTo == null || moment.isBefore(validTo));
        }
    }

    private final DeviceAssignmentRepository deviceAssignmentRepository;
    private final Map<String, VehicleOfDevice> cachedAssignmentByImei = new ConcurrentHashMap<>();

    public DeviceVehicleDirectory(DeviceAssignmentRepository deviceAssignmentRepository) {
        this.deviceAssignmentRepository = deviceAssignmentRepository;
    }

    @Transactional(readOnly = true)
    public Optional<VehicleOfDevice> findVehicleAt(String imei, Instant moment) {
        VehicleOfDevice cached = cachedAssignmentByImei.get(imei);
        if (cached != null && cached.covers(moment)) {
            return Optional.of(cached);
        }

        Optional<VehicleOfDevice> fromDatabase = deviceAssignmentRepository.findAssignmentActiveAt(imei, moment)
                .map(DeviceVehicleDirectory::toVehicleOfDevice);
        fromDatabase.ifPresent(found -> cachedAssignmentByImei.put(imei, found));
        return fromDatabase;
    }

    private static VehicleOfDevice toVehicleOfDevice(DeviceAssignment assignment) {
        Vehicle vehicle = assignment.getVehicle();
        Tenant owner = vehicle.getOwnerTenant();
        return new VehicleOfDevice(vehicle.getRegistrationNo(), owner == null ? null : owner.getCode(),
                assignment.getValidFrom(), assignment.getValidTo());
    }
}
