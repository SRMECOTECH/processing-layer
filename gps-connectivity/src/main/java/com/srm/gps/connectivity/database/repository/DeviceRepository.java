package com.srm.gps.connectivity.database.repository;

import com.srm.gps.connectivity.database.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByImei(String imei);
}
