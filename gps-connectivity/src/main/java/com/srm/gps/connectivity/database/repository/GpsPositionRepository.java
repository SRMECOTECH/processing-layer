package com.srm.gps.connectivity.database.repository;

import com.srm.gps.connectivity.database.entity.GpsPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GpsPositionRepository extends JpaRepository<GpsPosition, Long> {

    boolean existsByImeiAndRecordedAt(String imei, Instant recordedAt);

    /** The newest stored reading of a device. Uses the (imei, recorded_at) unique index. */
    Optional<GpsPosition> findFirstByImeiOrderByRecordedAtDesc(String imei);

    Optional<GpsPosition> findFirstByVehicleRegistrationNoOrderByRecordedAtDesc(String vehicleRegistrationNo);

    List<GpsPosition> findTop20ByOrderByStoredAtDesc();
}
