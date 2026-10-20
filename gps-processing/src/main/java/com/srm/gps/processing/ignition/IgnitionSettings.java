package com.srm.gps.processing.ignition;

/**
 * Tuning of the ignition tracker.
 *
 * @param virtualOffAfterSeconds   without an ignition wire: standing this long means the engine is off
 * @param idleSpeedKmph            engine on and at most this speed = idling (the 2-3 km/h threshold)
 * @param idleAlertSeconds         idling longer than this raises IDLING
 * @param moveSpeedKmph            at least this speed (or moveDistanceMeters since the last reading) = moving
 * @param moveDistanceMeters       see moveSpeedKmph
 * @param deviceDebounceReadings   readings a device-reported change must persist (1 = trust the wire at once)
 * @param virtualDebounceReadings  readings an inferred change must persist (inference is noisier)
 * @param maxCountedGapSeconds     engine time is not credited across a longer silence (Traccar EngineHoursHandler
 *                                 credits the whole gap; one lost hour of data would then add an hour of engine time)
 */
public record IgnitionSettings(long virtualOffAfterSeconds, double idleSpeedKmph, long idleAlertSeconds,
                               double moveSpeedKmph, double moveDistanceMeters, int deviceDebounceReadings,
                               int virtualDebounceReadings, long maxCountedGapSeconds) {

    public static IgnitionSettings defaults() {
        return new IgnitionSettings(300, 3, 300, 5, 50, 1, 2, 900);
    }
}
