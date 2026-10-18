package com.srm.gps.connectivity.database.repository;

import com.srm.gps.connectivity.database.entity.DeviceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeviceAssignmentRepository extends JpaRepository<DeviceAssignment, UUID> {

    /**
     * Which vehicle was this device fitted in at the given moment?
     * Uses the READING time, not "now", so readings delivered late still go to the right vehicle.
     */
    @Query("""
            select assignment from DeviceAssignment assignment
              join fetch assignment.vehicle vehicle
              left join fetch vehicle.ownerTenant
            where assignment.device.imei = :imei
              and assignment.validFrom <= :moment
              and (assignment.validTo is null or assignment.validTo > :moment)
            """)
    Optional<DeviceAssignment> findAssignmentActiveAt(String imei, Instant moment);

    boolean existsByDeviceImei(String imei);
}
