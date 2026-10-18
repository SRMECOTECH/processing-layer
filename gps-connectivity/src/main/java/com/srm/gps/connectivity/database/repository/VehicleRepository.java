package com.srm.gps.connectivity.database.repository;

import com.srm.gps.connectivity.database.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    Optional<Vehicle> findByRegistrationNo(String registrationNo);
}
