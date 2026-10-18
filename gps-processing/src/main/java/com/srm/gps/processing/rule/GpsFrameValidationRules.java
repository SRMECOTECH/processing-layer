package com.srm.gps.processing.rule;

import com.srm.gps.configuration.message.RawGpsFrame;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The checks every raw frame must pass. Each rule is easy to unit-test.
 */
public final class GpsFrameValidationRules {

    /** Device clocks drift a little; anything further in the future than this is a broken clock. */
    public static final Duration ALLOWED_CLOCK_DRIFT = Duration.ofMinutes(5);

    /** Above this a goods vehicle reading is treated as a device error. */
    public static final int MAXIMUM_BELIEVABLE_SPEED_KMPH = 150;

    private GpsFrameValidationRules() {
    }

    /**
     * @return the first problem found, in plain English, or empty when the frame is valid
     */
    public static Optional<String> findProblem(RawGpsFrame frame, Instant now) {
        if (frame.imei() == null || frame.imei().isBlank()) {
            return Optional.of("IMEI is missing");
        }
        if (frame.recordedAt() == null) {
            return Optional.of("Recording time is missing");
        }
        if (frame.recordedAt().isAfter(now.plus(ALLOWED_CLOCK_DRIFT))) {
            return Optional.of("Recording time " + frame.recordedAt() + " is in the future");
        }
        if (frame.latitude() == null || frame.longitude() == null) {
            return Optional.of("Latitude or longitude is missing");
        }
        if (frame.latitude() < -90 || frame.latitude() > 90) {
            return Optional.of("Latitude " + frame.latitude() + " is outside -90..90");
        }
        if (frame.longitude() < -180 || frame.longitude() > 180) {
            return Optional.of("Longitude " + frame.longitude() + " is outside -180..180");
        }
        if (frame.latitude() == 0 && frame.longitude() == 0) {
            return Optional.of("Position is 0,0 - the device has no GPS fix");
        }
        if (frame.speedKmph() == null) {
            return Optional.of("Speed is missing");
        }
        if (frame.speedKmph() < 0 || frame.speedKmph() > MAXIMUM_BELIEVABLE_SPEED_KMPH) {
            return Optional.of("Speed " + frame.speedKmph() + " km/h is outside 0.." + MAXIMUM_BELIEVABLE_SPEED_KMPH);
        }
        return Optional.empty();
    }
}
