package com.srm.gps.connectivity.database.entity;

import com.srm.gps.configuration.message.MovementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One stored GPS position. Table: gps_position.
 *
 * Tenant and vehicle are stored as readable codes (tenant code, registration number), not as foreign keys:
 * telemetry is written fast and read in reports, and it will later move to a time-series store.
 * Both are null when the device was not assigned to any vehicle at the time of the reading.
 */
@Entity
@Table(name = "gps_position")
public class GpsPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imei", nullable = false)
    private String imei;

    @Column(name = "tenant_code")
    private String tenantCode;

    @Column(name = "vehicle_registration_no")
    private String vehicleRegistrationNo;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "speed_kmph", nullable = false)
    private int speedKmph;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_status", nullable = false)
    private MovementStatus movementStatus;

    @Column(name = "distance_from_previous_m", nullable = false)
    private double distanceFromPreviousMeters;

    @Column(name = "seconds_since_previous", nullable = false)
    private long secondsSincePreviousReading;

    @Column(name = "arrived_out_of_order", nullable = false)
    private boolean arrivedOutOfOrder;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(name = "stored_at", nullable = false)
    private Instant storedAt = Instant.now();

    protected GpsPosition() {
        // for Hibernate
    }

    public GpsPosition(String imei, String tenantCode, String vehicleRegistrationNo, Instant recordedAt,
                       double latitude, double longitude, int speedKmph, MovementStatus movementStatus,
                       double distanceFromPreviousMeters, long secondsSincePreviousReading,
                       boolean arrivedOutOfOrder, Instant processedAt) {
        this.imei = imei;
        this.tenantCode = tenantCode;
        this.vehicleRegistrationNo = vehicleRegistrationNo;
        this.recordedAt = recordedAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedKmph = speedKmph;
        this.movementStatus = movementStatus;
        this.distanceFromPreviousMeters = distanceFromPreviousMeters;
        this.secondsSincePreviousReading = secondsSincePreviousReading;
        this.arrivedOutOfOrder = arrivedOutOfOrder;
        this.processedAt = processedAt;
    }

    public Long getId() {
        return id;
    }

    public String getImei() {
        return imei;
    }

    public String getTenantCode() {
        return tenantCode;
    }

    public String getVehicleRegistrationNo() {
        return vehicleRegistrationNo;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public int getSpeedKmph() {
        return speedKmph;
    }

    public MovementStatus getMovementStatus() {
        return movementStatus;
    }

    public double getDistanceFromPreviousMeters() {
        return distanceFromPreviousMeters;
    }

    public long getSecondsSincePreviousReading() {
        return secondsSincePreviousReading;
    }

    public boolean isArrivedOutOfOrder() {
        return arrivedOutOfOrder;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getStoredAt() {
        return storedAt;
    }
}
