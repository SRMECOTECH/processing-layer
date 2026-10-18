package com.srm.gps.application.masterdata;

import com.srm.gps.connectivity.database.entity.Device;
import com.srm.gps.connectivity.database.entity.DeviceAssignment;
import com.srm.gps.connectivity.database.entity.Tenant;
import com.srm.gps.connectivity.database.entity.Vehicle;
import com.srm.gps.connectivity.database.repository.DeviceAssignmentRepository;
import com.srm.gps.connectivity.database.repository.DeviceRepository;
import com.srm.gps.connectivity.database.repository.TenantRepository;
import com.srm.gps.connectivity.database.repository.VehicleRepository;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsPing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Makes sure the new gps_tracking database knows the devices the simulator is about to replay.
 *
 * In the real system master data (companies, vehicles, devices, assignments) is maintained by its own service.
 * For the simulation we derive it from the smart_truck pings: transporter -> tenant, asset -> vehicle,
 * device id -> device, and "device fitted in vehicle from its first ping" -> device_assignment.
 */
@Service
public class SimulationMasterDataSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(SimulationMasterDataSeeder.class);
    private static final String SIMULATED_DEVICE_PROTOCOL = "smart-truck-replay";

    private final TenantRepository tenantRepository;
    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceAssignmentRepository deviceAssignmentRepository;

    public SimulationMasterDataSeeder(TenantRepository tenantRepository,
                                      VehicleRepository vehicleRepository,
                                      DeviceRepository deviceRepository,
                                      DeviceAssignmentRepository deviceAssignmentRepository) {
        this.tenantRepository = tenantRepository;
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.deviceAssignmentRepository = deviceAssignmentRepository;
    }

    @Transactional
    public void registerVehiclesAndDevices(List<SmartTruckGpsPing> pings) {
        // The earliest ping of each device tells us its vehicle, its company, and since when it is fitted.
        Map<String, SmartTruckGpsPing> earliestPingOfEachDevice = pings.stream()
                .collect(Collectors.toMap(
                        SmartTruckGpsPing::deviceImei,
                        ping -> ping,
                        (first, second) -> first.recordedAt().isBefore(second.recordedAt()) ? first : second));

        earliestPingOfEachDevice.values().stream()
                .sorted(Comparator.comparing(SmartTruckGpsPing::deviceImei))
                .forEach(this::registerOneDevice);
    }

    private void registerOneDevice(SmartTruckGpsPing earliestPing) {
        Tenant tenant = findOrCreateTenant(earliestPing.transporterName());
        Vehicle vehicle = findOrCreateVehicle(earliestPing.vehicleRegistration(), tenant);
        Device device = findOrCreateDevice(earliestPing.deviceImei());

        if (!deviceAssignmentRepository.existsByDeviceImei(device.getImei())) {
            deviceAssignmentRepository.save(new DeviceAssignment(device, vehicle, earliestPing.recordedAt()));
            LOG.info("Registered device {} in vehicle {} of tenant {} from {}",
                    device.getImei(), vehicle.getRegistrationNo(), tenant.getCode(), earliestPing.recordedAt());
        }
    }

    private Tenant findOrCreateTenant(String transporterName) {
        String code = TenantCodeFormatter.toTenantCode(transporterName);
        String legalName = transporterName != null ? transporterName : "Unknown company";
        return tenantRepository.findByCode(code)
                .orElseGet(() -> tenantRepository.save(new Tenant(code, legalName)));
    }

    private Vehicle findOrCreateVehicle(String registrationNo, Tenant ownerTenant) {
        return vehicleRepository.findByRegistrationNo(registrationNo)
                .orElseGet(() -> vehicleRepository.save(new Vehicle(registrationNo, ownerTenant)));
    }

    private Device findOrCreateDevice(String imei) {
        return deviceRepository.findByImei(imei)
                .orElseGet(() -> deviceRepository.save(new Device(imei, SIMULATED_DEVICE_PROTOCOL)));
    }
}
