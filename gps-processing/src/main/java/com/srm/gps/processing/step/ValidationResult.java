package com.srm.gps.processing.step;

import com.srm.gps.configuration.message.RawGpsFrame;

/**
 * Outcome of step 1: either a valid frame, or the problem found in plain English.
 *
 * @param validFrame the parsed frame when valid, otherwise null
 * @param problem    why the frame is invalid, otherwise null
 * @param imei       the frame's IMEI when it could be read (also for invalid frames), otherwise null
 */
public record ValidationResult(RawGpsFrame validFrame, String problem, String imei) {

    public static ValidationResult valid(RawGpsFrame frame) {
        return new ValidationResult(frame, null, frame.imei());
    }

    public static ValidationResult invalid(String problem, String imei) {
        return new ValidationResult(null, problem, imei);
    }

    public boolean isValid() {
        return problem == null;
    }
}
