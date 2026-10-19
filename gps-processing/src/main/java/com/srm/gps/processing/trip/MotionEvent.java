package com.srm.gps.processing.trip;

import java.time.Instant;

/**
 * The vehicle started moving or stopped, at the given reading (Traccar's deviceMoving / deviceStopped).
 * A MOVING event is back-dated to the reading where the vehicle actually started rolling.
 */
public record MotionEvent(Type type, Instant recordedAt, double latitude, double longitude) {

    public enum Type { MOVING, STOPPED }

    static MotionEvent at(Type type, WindowReading reading) {
        return new MotionEvent(type, reading.recordedAt(), reading.latitude(), reading.longitude());
    }
}
