package com.srm.gps.processing.step;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.processing.rule.GpsFrameValidationRules;

import java.time.Instant;
import java.util.Optional;

/**
 * Step 1: turns JSON text into a {@link RawGpsFrame} and checks it.
 * The rules themselves live in {@link GpsFrameValidationRules}.
 */
public class ValidateRawFrameStep {

    public ValidationResult validate(String rawFrameJson, Instant now) {
        RawGpsFrame frame;
        try {
            frame = GpsMessageJson.fromJson(rawFrameJson, RawGpsFrame.class);
        } catch (GpsMessageJson.InvalidGpsMessageException exception) {
            return ValidationResult.invalid("Frame is not valid JSON", null);
        }

        Optional<String> problem = GpsFrameValidationRules.findProblem(frame, now);
        if (problem.isPresent()) {
            String imei = frame.imei() == null || frame.imei().isBlank() ? null : frame.imei();
            return ValidationResult.invalid(problem.get(), imei);
        }
        return ValidationResult.valid(frame);
    }
}
