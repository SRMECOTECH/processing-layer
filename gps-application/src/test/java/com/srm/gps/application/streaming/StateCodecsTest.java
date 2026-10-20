package com.srm.gps.application.streaming;

import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.processing.ignition.IgnitionProcessor;
import com.srm.gps.processing.ignition.IgnitionReading;
import com.srm.gps.processing.ignition.IgnitionSettings;
import com.srm.gps.processing.ignition.IgnitionState;
import com.srm.gps.processing.speed.SpeedReading;
import com.srm.gps.processing.speed.SpeedRule;
import com.srm.gps.processing.speed.SpeedViolationProcessor;
import com.srm.gps.processing.speed.SpeedViolationState;
import com.srm.gps.processing.state.VehicleReading;
import com.srm.gps.processing.state.VehicleState;
import com.srm.gps.processing.state.VehicleStateProcessor;
import com.srm.gps.processing.stoppage.StoppageDetector;
import com.srm.gps.processing.stoppage.StoppageReading;
import com.srm.gps.processing.stoppage.StoppageSettings;
import com.srm.gps.processing.stoppage.StoppageState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every consumer state must survive the trip through a Redis hash, reading after reading. */
class StateCodecsTest {

    private static final Instant T0 = Instant.parse("2026-10-20T06:00:00Z");

    @Test
    void vehicleStateRoundTripsAndKeepsUpdating() {
        VehicleState state = null;
        for (int i = 0; i < 5; i++) {
            VehicleState updated = VehicleStateProcessor.update(state, new VehicleReading("I", "XX01", "t", T0.plusSeconds(60L * i),
                    22.8 + i * 0.001, 86.2, 30, true, i % 2 == 0, i * 100.0, DistanceSource.GPS, i * 100.0, null, 60, false),
                    VehicleStateProcessor.Settings.defaults()).state();
            state = StateCodecs.vehicle(redisRoundTrip(StateCodecs.vehicle(updated)));
            assertEquals(updated.lastRecordedAt(), state.lastRecordedAt());
            assertEquals(updated.kalmanDistanceMeters(), state.kalmanDistanceMeters());
            assertArrayEquals(updated.kalmanState(), state.kalmanState());
        }
    }

    @Test
    void speedStateRoundTrips() {
        SpeedViolationState state = SpeedViolationState.initial();
        for (int i = 0; i < 6; i++) {
            SpeedViolationState updated = SpeedViolationProcessor.update(state,
                    new SpeedReading(T0.plusSeconds(30L * i), 22.8 + i * 0.006, 86.2, 80, i == 0 ? null : 1000L * i, false),
                    SpeedRule.defaults()).state();
            state = StateCodecs.speed(redisRoundTrip(StateCodecs.speed(updated, SpeedRule.defaults())));
            assertEquals(updated, state);
        }
    }

    @Test
    void ignitionAndStoppageStatesRoundTrip() {
        IgnitionState ignition = IgnitionState.initial();
        StoppageState stoppage = StoppageState.initial();
        for (int i = 0; i < 8; i++) {
            Instant t = T0.plusSeconds(60L * i);
            IgnitionState nextIgnition = IgnitionProcessor.update(ignition,
                    new IgnitionReading(t, 22.8, 86.2, 0, 0, i < 4, false), IgnitionSettings.defaults()).state();
            ignition = StateCodecs.ignition(redisRoundTrip(StateCodecs.ignition(nextIgnition)));
            assertEquals(nextIgnition, ignition);

            StoppageState nextStoppage = StoppageDetector.update(stoppage,
                    new StoppageReading(t, 22.8, 86.2, 0, i < 4, "DEVICE", false), StoppageSettings.defaults()).state();
            stoppage = StateCodecs.stoppage(redisRoundTrip(StateCodecs.stoppage(nextStoppage)));
            assertEquals(nextStoppage, stoppage);
        }
    }

    @Test
    void speedRuleRoundTrips() {
        SpeedRule rule = new SpeedRule("tenant:abc", 45, 30, 2, 3, 8, 0.1, 120, 2, true, 4);
        assertEquals(rule, StateCodecs.rule("tenant:abc", redisRoundTrip(StateCodecs.rule(rule))));
    }

    /** Redis gives every value back as a string, and nothing for fields never written. */
    private static Map<String, String> redisRoundTrip(Map<String, String> fields) {
        return new HashMap<>(fields);
    }
}
