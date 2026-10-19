package com.srm.gps.processing.trip;

import java.time.Instant;
import java.util.List;

/**
 * Everything the trip detection remembers about one device between two readings
 * (Traccar keeps the same on tc_devices: motionstreak, motiontime, motionlatitude, motionlongitude,
 * plus the recent positions in its cache). Stored as one row of vehicle_motion_state.
 *
 * @param imei              the device
 * @param assignment        device_assignment of the newest reading (null: not fitted in a vehicle)
 * @param moving            true between a MOVING and a STOPPED event (Traccar's motionStreak)
 * @param eventRecordedAt   time of the last MOVING / STOPPED event, or of the first reading
 * @param eventLatitude     where that event happened: a trip starts once the vehicle is minDistance away from here
 * @param eventLongitude    see eventLatitude
 * @param lastRecordedAt    the newest reading applied; older or equal readings are skipped (null: device never seen)
 * @param window            in-order readings of the last minDuration, oldest first
 * @param openTrip          the trip going on now, or null
 * @param openStop          the stop going on now, or null
 */
public record MotionState(
        String imei,
        VehicleAssignment assignment,
        boolean moving,
        Instant eventRecordedAt,
        double eventLatitude,
        double eventLongitude,
        Instant lastRecordedAt,
        List<WindowReading> window,
        DetectedTrip openTrip,
        DetectedStop openStop) {

    public MotionState {
        window = window == null ? List.of() : List.copyOf(window);
    }

    /** A device whose readings the trip detection has never seen. */
    public static MotionState unknownDevice(String imei) {
        return new MotionState(imei, null, false, null, 0, 0, null, List.of(), null, null);
    }

    public boolean neverSeen() {
        return lastRecordedAt == null;
    }
}
