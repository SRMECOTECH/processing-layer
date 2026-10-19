package com.srm.gps.application.trip;

import com.srm.gps.processing.trip.DetectedStop;
import com.srm.gps.processing.trip.DetectedTrip;
import com.srm.gps.processing.trip.VehicleAssignment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;

import static com.srm.gps.application.trip.VehicleMotionStateStore.utc;

/**
 * Writes gps_trip, gps_stop and gps_trip_late_reading.
 *
 * Trips and stops are upserted by (imei, start_recorded_at): the open trip is updated with every reading,
 * and a replayed reading writes the very same values again instead of a second row.
 */
@Repository
public class TripAndStopStore {

    private static final String SAVE_TRIP = """
            INSERT INTO gps_trip (imei, tenant_code, vehicle_registration_no, start_recorded_at, end_recorded_at,
                start_latitude, start_longitude, end_latitude, end_longitude, distance_m, duration_s,
                max_speed_kmph, avg_speed_kmph, reading_count, status, updated_at)
            VALUES (:imei, :tenantCode, :vehicleRegistrationNo, :startRecordedAt, :endRecordedAt,
                :startLatitude, :startLongitude, :endLatitude, :endLongitude, :distanceMeters, :durationSeconds,
                :maxSpeedKmph, :averageSpeedKmph, :readingCount, :status, now())
            ON CONFLICT (imei, start_recorded_at) DO UPDATE SET
                tenant_code = EXCLUDED.tenant_code,
                vehicle_registration_no = EXCLUDED.vehicle_registration_no,
                end_recorded_at = EXCLUDED.end_recorded_at,
                end_latitude = EXCLUDED.end_latitude,
                end_longitude = EXCLUDED.end_longitude,
                distance_m = EXCLUDED.distance_m,
                duration_s = EXCLUDED.duration_s,
                max_speed_kmph = EXCLUDED.max_speed_kmph,
                avg_speed_kmph = EXCLUDED.avg_speed_kmph,
                reading_count = EXCLUDED.reading_count,
                status = EXCLUDED.status,
                updated_at = now()
            """;

    private static final String SAVE_STOP = """
            INSERT INTO gps_stop (imei, tenant_code, vehicle_registration_no, start_recorded_at, end_recorded_at,
                latitude, longitude, duration_s, reading_count, status, updated_at)
            VALUES (:imei, :tenantCode, :vehicleRegistrationNo, :startRecordedAt, :endRecordedAt,
                :latitude, :longitude, :durationSeconds, :readingCount, :status, now())
            ON CONFLICT (imei, start_recorded_at) DO UPDATE SET
                tenant_code = EXCLUDED.tenant_code,
                vehicle_registration_no = EXCLUDED.vehicle_registration_no,
                end_recorded_at = EXCLUDED.end_recorded_at,
                duration_s = EXCLUDED.duration_s,
                reading_count = EXCLUDED.reading_count,
                status = EXCLUDED.status,
                updated_at = now()
            """;

    /**
     * Notes the late reading once (the primary key ignores a redelivery) and, if it falls inside a trip,
     * counts it on that trip. Its distance is NOT added: late data never changes a trip's distance.
     */
    private static final String NOTE_LATE_READING = """
            WITH noted AS (
                INSERT INTO gps_trip_late_reading (imei, recorded_at, trip_start_recorded_at)
                VALUES (:imei, :recordedAt, (
                    SELECT start_recorded_at FROM gps_trip
                    WHERE imei = :imei AND start_recorded_at <= :recordedAt AND end_recorded_at >= :recordedAt
                    ORDER BY start_recorded_at DESC LIMIT 1))
                ON CONFLICT (imei, recorded_at) DO NOTHING
                RETURNING trip_start_recorded_at)
            UPDATE gps_trip SET late_reading_count = late_reading_count + 1, updated_at = now()
            WHERE imei = :imei AND start_recorded_at = (SELECT trip_start_recorded_at FROM noted)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TripAndStopStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveTrip(String imei, DetectedTrip trip) {
        jdbc.update(SAVE_TRIP, owner(imei, trip.assignment())
                .addValue("startRecordedAt", utc(trip.startRecordedAt()))
                .addValue("endRecordedAt", utc(trip.endRecordedAt()))
                .addValue("startLatitude", trip.startLatitude())
                .addValue("startLongitude", trip.startLongitude())
                .addValue("endLatitude", trip.endLatitude())
                .addValue("endLongitude", trip.endLongitude())
                .addValue("distanceMeters", Math.round(trip.distanceMeters() * 10) / 10.0)
                .addValue("durationSeconds", trip.durationSeconds())
                .addValue("maxSpeedKmph", trip.maxSpeedKmph())
                .addValue("averageSpeedKmph", Math.round(trip.averageSpeedKmph() * 10) / 10.0)
                .addValue("readingCount", trip.readingCount())
                .addValue("status", trip.status().name()));
    }

    public void saveStop(String imei, DetectedStop stop) {
        jdbc.update(SAVE_STOP, owner(imei, stop.assignment())
                .addValue("startRecordedAt", utc(stop.startRecordedAt()))
                .addValue("endRecordedAt", utc(stop.endRecordedAt()))
                .addValue("latitude", stop.latitude())
                .addValue("longitude", stop.longitude())
                .addValue("durationSeconds", stop.durationSeconds())
                .addValue("readingCount", stop.readingCount())
                .addValue("status", stop.status().name()));
    }

    /** @return true when the late reading was seen for the first time and fell inside a trip */
    public boolean noteLateReading(String imei, Instant recordedAt) {
        return jdbc.update(NOTE_LATE_READING, new MapSqlParameterSource()
                .addValue("imei", imei)
                .addValue("recordedAt", utc(recordedAt))) == 1;
    }

    private static MapSqlParameterSource owner(String imei, VehicleAssignment assignment) {
        return new MapSqlParameterSource()
                .addValue("imei", imei)
                .addValue("tenantCode", assignment == null ? null : assignment.tenantCode(), Types.VARCHAR)
                .addValue("vehicleRegistrationNo", assignment == null ? null : assignment.vehicleRegistrationNo(), Types.VARCHAR);
    }
}
