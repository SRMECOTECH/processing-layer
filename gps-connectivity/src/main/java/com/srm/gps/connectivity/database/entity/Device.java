package com.srm.gps.connectivity.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A tracking device fitted in a vehicle. Table: device.
 * The device identifies itself only by its {@code imei}; which vehicle it sits in is a {@link DeviceAssignment}.
 */
@Entity
@Table(name = "device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "imei", nullable = false, unique = true)
    private String imei;

    /** GPS, FUEL, TEMPERATURE, DOOR, OBD ... */
    @Column(name = "kind", nullable = false)
    private String kind = "GPS";

    /** Name of the protocol definition used to decode this device's frames. */
    @Column(name = "protocol")
    private String protocol;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Device() {
        // for Hibernate
    }

    public Device(String imei, String protocol) {
        this.imei = imei;
        this.protocol = protocol;
    }

    public UUID getId() {
        return id;
    }

    public String getImei() {
        return imei;
    }

    public String getKind() {
        return kind;
    }

    public String getProtocol() {
        return protocol;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
