package com.srm.gps.connectivity.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * "Device X was fitted in vehicle Y from validFrom until validTo". Table: device_assignment.
 *
 * Stored as a period, never as "current vehicle", because devices buffer readings and deliver them
 * hours later: a late reading must be matched to the vehicle the device was in AT THE TIME OF THE READING.
 * {@code validTo == null} means the device is still fitted.
 */
@Entity
@Table(name = "device_assignment")
public class DeviceAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    protected DeviceAssignment() {
        // for Hibernate
    }

    public DeviceAssignment(Device device, Vehicle vehicle, Instant validFrom) {
        this.device = device;
        this.vehicle = vehicle;
        this.validFrom = validFrom;
    }

    public UUID getId() {
        return id;
    }

    public Device getDevice() {
        return device;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }
}
