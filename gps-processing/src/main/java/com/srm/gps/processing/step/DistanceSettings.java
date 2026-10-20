package com.srm.gps.processing.step;

/**
 * Tuning of the distance step - Traccar's {@code coordinates.filter / coordinates.minError / coordinates.maxError}
 * and {@code filter.maxSpeed}, with two refinements:
 *  - jitter is only clamped while the device itself says it is (nearly) standing, so a slow-moving truck's
 *    real metres are never thrown away;
 *  - an impossible jump is not counted but the new position is kept (it is where the device is now),
 *    so a relocated device does not stay "stuck" at its old spot.
 *
 * @param jitterMeters         a move shorter than this while standing is GPS noise (Traccar minError)
 * @param jitterMaxSpeedKmph   only clamp jitter when the reported speed is below this
 * @param maxImpliedSpeedKmph  a move needing more than this speed is not counted (Traccar filter.maxSpeed)
 * @param minJumpMeters        ... and must also be at least this long (short hops over short gaps are normal noise)
 */
public record DistanceSettings(double jitterMeters, int jitterMaxSpeedKmph, double maxImpliedSpeedKmph, double minJumpMeters) {

    public static DistanceSettings defaults() {
        return new DistanceSettings(10, 5, 200, 500);
    }
}
