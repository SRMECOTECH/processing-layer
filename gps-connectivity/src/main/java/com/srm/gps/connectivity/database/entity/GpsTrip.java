package com.srm.gps.connectivity.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A trip detected by the trip detection (consumer group gps-trip-service). Table: gps_trip.
 * One row per (imei, start_recorded_at). While status is OPEN the vehicle is still driving and the end moves.
 */
@Entity
@Table(name = "gps_trip")
public class GpsTrip {

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

    @Column(name = "start_latitude", nullable = false)
    private double startLatitude;

    @Column(name = "start_longitude", nullable = false)
    private double startLongitude;

    @Column(name = "end_latitude", nullable = false)
    private double endLatitude;

    @Column(name = "end_longitude", nullable = false)
    private double endLongitude;

    @Column(name = "distance_m", nullable = false)
    private double distanceMeters;

    @Column(name = "duration_s", nullable = false)
    private long durationSeconds;

    @Column(name = "max_speed_kmph", nullable = false)
    private int maxSpeedKmph;

    @Column(name = "avg_speed_kmph", nullable = false)
    private double averageSpeedKmph;

    @Column(name = "reading_count", nullable = false)
    private int readingCount;

    @Column(name = "late_reading_count", nullable = false)
    private int lateReadingCount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GpsTrip() {
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

    public double getStartLatitude() {
        return startLatitude;
    }

    public double getStartLongitude() {
        return startLongitude;
    }

    public double getEndLatitude() {
        return endLatitude;
    }

    public double getEndLongitude() {
        return endLongitude;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public int getMaxSpeedKmph() {
        return maxSpeedKmph;
    }

    public double getAverageSpeedKmph() {
        return averageSpeedKmph;
    }

    public int getReadingCount() {
        return readingCount;
    }

    public int getLateReadingCount() {
        return lateReadingCount;
    }

    public String getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
