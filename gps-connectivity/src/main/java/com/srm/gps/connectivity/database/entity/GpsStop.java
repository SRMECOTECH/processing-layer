package com.srm.gps.connectivity.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A stop detected by the trip detection (consumer group gps-trip-service). Table: gps_stop.
 * One row per (imei, start_recorded_at). While status is OPEN the vehicle is still parked and the end moves.
 */
@Entity
@Table(name = "gps_stop")
public class GpsStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imei", nullable = false)
    private String imei;

    @Column(name = "tenant_code")
    private String tenantCode;

    @Column(name = "vehicle_registration_no")
    private String vehicleRegistrationNo;

    @Column(name = "start_recorded_at", nullable = false)
    private Instant startRecordedAt;

    @Column(name = "end_recorded_at", nullable = false)
    private Instant endRecordedAt;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "duration_s", nullable = false)
    private long durationSeconds;

    @Column(name = "reading_count", nullable = false)
    private int readingCount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GpsStop() {
        // read-only for Hibernate: rows are written by the trip detection with plain SQL (upserts)
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

    public Instant getStartRecordedAt() {
        return startRecordedAt;
    }

    public Instant getEndRecordedAt() {
        return endRecordedAt;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public int getReadingCount() {
        return readingCount;
    }

    public String getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
