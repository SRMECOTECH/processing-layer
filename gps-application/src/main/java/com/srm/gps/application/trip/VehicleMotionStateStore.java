package com.srm.gps.application.trip;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.trip.DetectedStop;
import com.srm.gps.processing.trip.DetectedTrip;
import com.srm.gps.processing.trip.MotionState;
import com.srm.gps.processing.trip.VehicleAssignment;
import com.srm.gps.processing.trip.WindowReading;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes vehicle_motion_state: the trip detection's memory of every device.
 *
 * Plain SQL instead of Hibernate, because the write is a guarded upsert:
 * "insert, or update only if this reading is newer than the last one applied".
 */
@Repository
public class VehicleMotionStateStore {

    private static final String FIND = "SELECT * FROM vehicle_motion_state WHERE imei = :imei";

    private static final String SAVE_IF_NEWER = """
            INSERT INTO vehicle_motion_state (imei, assignment_id, tenant_code, vehicle_registration_no, motion_streak,
                event_recorded_at, event_latitude, event_longitude, last_recorded_at,
                reading_window, open_trip, open_stop, updated_at)
            VALUES (:imei, :assignmentId, :tenantCode, :vehicleRegistrationNo, :moving,
                :eventRecordedAt, :eventLatitude, :eventLongitude, :lastRecordedAt,
                CAST(:window AS jsonb), CAST(:openTrip AS jsonb), CAST(:openStop AS jsonb), now())
            ON CONFLICT (imei) DO UPDATE SET
                assignment_id = EXCLUDED.assignment_id,
                tenant_code = EXCLUDED.tenant_code,
                vehicle_registration_no = EXCLUDED.vehicle_registration_no,
                motion_streak = EXCLUDED.motion_streak,
                event_recorded_at = EXCLUDED.event_recorded_at,
                event_latitude = EXCLUDED.event_latitude,
                event_longitude = EXCLUDED.event_longitude,
                last_recorded_at = EXCLUDED.last_recorded_at,
                reading_window = EXCLUDED.reading_window,
                open_trip = EXCLUDED.open_trip,
                open_stop = EXCLUDED.open_stop,
                updated_at = now()
            WHERE vehicle_motion_state.last_recorded_at < EXCLUDED.last_recorded_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public VehicleMotionStateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MotionState> find(String imei) {
        return jdbc.query(FIND, new MapSqlParameterSource("imei", imei), VehicleMotionStateStore::toMotionState)
                .stream().findFirst();
    }

    /**
     * Saves the state, but only if it is newer than what is stored.
     *
     * @return false when a reading at least as new was already applied (by a redelivery, or by another consumer
     *         just after a rebalance): then nothing may be written for this reading
     */
    public boolean saveIfNewer(MotionState state) {
        VehicleAssignment assignment = state.assignment();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("imei", state.imei())
                .addValue("assignmentId", assignment == null || assignment.assignmentId() == null
                        ? null : UUID.fromString(assignment.assignmentId()), Types.OTHER)
                .addValue("tenantCode", assignment == null ? null : assignment.tenantCode(), Types.VARCHAR)
                .addValue("vehicleRegistrationNo", assignment == null ? null : assignment.vehicleRegistrationNo(), Types.VARCHAR)
                .addValue("moving", state.moving())
                .addValue("eventRecordedAt", utc(state.eventRecordedAt()))
                .addValue("eventLatitude", state.eventLatitude())
                .addValue("eventLongitude", state.eventLongitude())
                .addValue("lastRecordedAt", utc(state.lastRecordedAt()))
                .addValue("window", GpsMessageJson.toJson(state.window()), Types.VARCHAR)
                .addValue("openTrip", state.openTrip() == null ? null : GpsMessageJson.toJson(state.openTrip()), Types.VARCHAR)
                .addValue("openStop", state.openStop() == null ? null : GpsMessageJson.toJson(state.openStop()), Types.VARCHAR);
        return jdbc.update(SAVE_IF_NEWER, parameters) == 1;
    }

    private static MotionState toMotionState(ResultSet row, int rowNumber) throws SQLException {
        UUID assignmentId = row.getObject("assignment_id", UUID.class);
        VehicleAssignment assignment = assignmentId == null ? null : new VehicleAssignment(
                assignmentId.toString(), row.getString("tenant_code"), row.getString("vehicle_registration_no"));
        String openTrip = row.getString("open_trip");
        String openStop = row.getString("open_stop");
        return new MotionState(
                row.getString("imei"),
                assignment,
                row.getBoolean("motion_streak"),
                instant(row, "event_recorded_at"),
                row.getDouble("event_latitude"),
                row.getDouble("event_longitude"),
                instant(row, "last_recorded_at"),
                List.of(GpsMessageJson.fromJson(row.getString("reading_window"), WindowReading[].class)),
                openTrip == null ? null : GpsMessageJson.fromJson(openTrip, DetectedTrip.class),
                openStop == null ? null : GpsMessageJson.fromJson(openStop, DetectedStop.class));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
