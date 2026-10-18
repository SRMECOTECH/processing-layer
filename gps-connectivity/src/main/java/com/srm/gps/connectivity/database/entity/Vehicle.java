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
 * A truck. Table: vehicle.
 * {@code registrationNo} is globally unique: one truck is one truck, whoever registers it.
 * A vehicle without an owner tenant is a "market vehicle".
 */
@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "registration_no", nullable = false, unique = true)
    private String registrationNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_tenant_id")
    private Tenant ownerTenant;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Vehicle() {
        // for Hibernate
    }

    public Vehicle(String registrationNo, Tenant ownerTenant) {
        this.registrationNo = registrationNo;
        this.ownerTenant = ownerTenant;
    }

    public UUID getId() {
        return id;
    }

    public String getRegistrationNo() {
        return registrationNo;
    }

    public Tenant getOwnerTenant() {
        return ownerTenant;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
