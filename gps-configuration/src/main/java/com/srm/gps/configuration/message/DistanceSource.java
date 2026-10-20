package com.srm.gps.configuration.message;

/**
 * Where a reading's distance came from (Traccar's DistanceHandler rules, see CalculateMovementStep).
 */
public enum DistanceSource {
    /** First reading of the device: nothing to measure from. */
    FIRST_READING,
    /** Great-circle distance between this and the previous position. */
    GPS,
    /** The device's own odometer delta (preferred when present and believable). */
    DEVICE_ODOMETER,
    /** The move was smaller than GPS noise while standing: position clamped to the previous one, distance 0. */
    JITTER_FILTERED,
    /** The move implies an impossible speed (GPS glitch or relocation): not counted. */
    JUMP_FILTERED,
    /** Older than a reading already processed: distance not measured. */
    OUT_OF_ORDER
}
