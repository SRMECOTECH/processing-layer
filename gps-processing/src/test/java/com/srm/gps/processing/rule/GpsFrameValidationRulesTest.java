package com.srm.gps.processing.rule;

import com.srm.gps.configuration.message.RawGpsFrame;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsFrameValidationRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final Instant ONE_HOUR_AGO = NOW.minusSeconds(3600);

    @Test
    void aNormalFrameHasNoProblem() {
        RawGpsFrame frame = new RawGpsFrame("0861000000000001", ONE_HOUR_AGO, 22.797258, 86.209573, 40);

        assertTrue(GpsFrameValidationRules.findProblem(frame, NOW).isEmpty());
    }

    @Test
    void aFrameWithoutImeiIsRejected() {
        RawGpsFrame frame = new RawGpsFrame(" ", ONE_HOUR_AGO, 22.79, 86.20, 40);

        assertEquals("IMEI is missing", GpsFrameValidationRules.findProblem(frame, NOW).orElseThrow());
    }

    @Test
    void aFrameFromTheFutureIsRejected() {
        RawGpsFrame frame = new RawGpsFrame("0861000000000001", NOW.plusSeconds(3600), 22.79, 86.20, 40);

        assertTrue(GpsFrameValidationRules.findProblem(frame, NOW).orElseThrow().contains("in the future"));
    }

    @Test
    void aFrameWithLatitudeOutOfRangeIsRejected() {
        RawGpsFrame frame = new RawGpsFrame("0861000000000001", ONE_HOUR_AGO, 95.0, 86.20, 40);

        assertTrue(GpsFrameValidationRules.findProblem(frame, NOW).orElseThrow().startsWith("Latitude 95.0"));
    }

    @Test
    void aFrameWithoutGpsFixIsRejected() {
        RawGpsFrame frame = new RawGpsFrame("0861000000000001", ONE_HOUR_AGO, 0.0, 0.0, 0);

        assertTrue(GpsFrameValidationRules.findProblem(frame, NOW).orElseThrow().contains("no GPS fix"));
    }

    @Test
    void anUnbelievableSpeedIsRejected() {
        RawGpsFrame frame = new RawGpsFrame("0861000000000001", ONE_HOUR_AGO, 22.79, 86.20, 400);

        assertTrue(GpsFrameValidationRules.findProblem(frame, NOW).orElseThrow().startsWith("Speed 400"));
    }
}
