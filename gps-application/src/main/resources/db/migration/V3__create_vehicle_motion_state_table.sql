-- State of the trip detection (consumer group gps-trip-service): one row per device.
-- Traccar keeps the same on tc_devices (motionstreak, motiontime, motionlatitude, motionlongitude)
-- plus the recent positions in memory. Here it is one row, updated in the SAME transaction as the
-- gps_trip / gps_stop rows it produces, so a trip can never be written twice or lost half-way.
--
-- Every update is guarded by "last_recorded_at < the new reading's time": a reading that is late,
-- or delivered again by Kafka, updates 0 rows and changes nothing.

CREATE TABLE vehicle_motion_state (
    imei                     varchar(50)      PRIMARY KEY,
    -- the device_assignment of the newest reading; a change closes the open trip (null: not in a vehicle)
    assignment_id            uuid,
    tenant_code              varchar(40),
    vehicle_registration_no  varchar(50),
    -- true between a "started moving" and a "stopped" decision (Traccar's motionStreak)
    motion_streak            boolean          NOT NULL,
    -- where and when the vehicle last started moving or stopped
    event_recorded_at        timestamptz      NOT NULL,
    event_latitude           double precision NOT NULL,
    event_longitude          double precision NOT NULL,
    -- the newest reading applied: older or equal readings are skipped
    last_recorded_at         timestamptz      NOT NULL,
    -- in-order readings of the last minDuration (a few dozen small objects), oldest first.
    -- Named reading_window because WINDOW is a reserved word in PostgreSQL.
    reading_window           jsonb            NOT NULL,
    -- the trip or stop going on now (the same values as its OPEN row in gps_trip / gps_stop), or null
    open_trip                jsonb,
    open_stop                jsonb,
    updated_at               timestamptz      NOT NULL DEFAULT now()
);
