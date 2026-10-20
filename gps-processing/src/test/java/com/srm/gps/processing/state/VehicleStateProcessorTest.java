package com.srm.gps.processing.state;

import com.srm.gps.configuration.message.DistanceSource;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.srm.gps.processing.TestTrack.at;
import static com.srm.gps.processing.TestTrack.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleStateProcessorTest {

    private static final VehicleStateProcessor.Settings SETTINGS = VehicleStateProcessor.Settings.defaults();

    @Test
    void headingPointsTheWayTheVehicleDrove() {
        VehicleState state = apply(null, 0, 0, 0, 30, true);
        state = apply(state, 60, 0, 500, 30, true);     // due north
        assertEquals(0, state.headingDegrees(), 0.5);
        state = apply(state, 120, 500, 500, 30, true);   // due east
        assertEquals(90, state.headingDegrees(), 0.5);
        state = apply(state, 180, 501, 500, 0, false);   // 1 m: heading kept
        assertEquals(90, state.headingDegrees(), 0.5);
    }

    @Test
    void aVehicleStandingStillFromItsFirstReadingHasNoHeadingYet() {
        VehicleState state = apply(null, 0, 0, 0, 0, false);
        state = apply(state, 60, 1, 0, 0, false);
        state = apply(state, 120, 0, 1, 0, false);
        assertEquals(null, state.headingDegrees());
        assertEquals(3, state.readings());
    }

    @Test
    void aParkedVehicleReportingEveryMinuteKeepsOneConvergingFilter() {
        VehicleState state = apply(null, 0, 0, 0, 0, false);
        for (int i = 1; i <= 20; i++) {
            state = apply(state, i * 60L, 0, 0, 0, false);   // jitter already clamped by processing: same point
        }
        assertEquals(0, state.kalmanRejectedInARow());
        assertTrue(state.kalmanCovariance()[0] < 144, "position variance should shrink below the first fix: " + state.kalmanCovariance()[0]);
        assertEquals(0, state.kalmanDistanceMeters());
    }

    @Test
    void theKalmanTrackIsSmootherThanNoisyGpsAndItsDistanceIsCloserToTheTruth() {
        Random noise = new Random(7);
        VehicleState state = null;
        double rawDistance = 0;
        double[] previous = null;
        for (int i = 0; i <= 60; i++) {
            double east = i * 100.0 + noise.nextGaussian() * 10;   // 100 m every 10 s (36 km/h), 10 m GPS noise
            double north = noise.nextGaussian() * 10;
            state = apply(state, i * 10L, east, north, 36, true);
            double[] here = {east, north};
            if (previous != null) {
                rawDistance += Math.hypot(here[0] - previous[0], here[1] - previous[1]);
            }
            previous = here;
        }
        double truth = 6000;
        assertTrue(Math.abs(state.kalmanDistanceMeters() - truth) < Math.abs(rawDistance - truth),
                "kalman " + state.kalmanDistanceMeters() + " raw " + rawDistance);
        assertEquals(36, state.kalmanSpeedKmph(), 6);
    }

    @Test
    void aLongSilenceRestartsTheFilterButKeepsTheCountedDistance() {
        VehicleState state = apply(null, 0, 0, 0, 30, true);
        double[] p = point(9000, 0);
        VehicleState after = VehicleStateProcessor.update(state, new VehicleReading("I", "XX01", "t", at(621), p[0], p[1], 0, true,
                null, 9000, DistanceSource.GPS, 9000.0, null, 621, false), SETTINGS).state();
        assertEquals(9000, after.kalmanDistanceMeters(), 0.1);
        assertEquals(p[0], after.originLatitude(), 1e-9);   // restarted at the new place
    }

    @Test
    void lateReadingsOnlyCountAsLate() {
        VehicleState state = apply(null, 60, 0, 0, 30, true);
        double[] p = point(999, 0);
        VehicleStateProcessor.Update update = VehicleStateProcessor.update(state, new VehicleReading("I", null, null, at(10),
                p[0], p[1], 50, true, null, 0, DistanceSource.OUT_OF_ORDER, null, null, 0, true), SETTINGS);
        assertEquals(1, update.state().outOfOrderReadings());
        assertEquals(state.latitude(), update.state().latitude());
    }

    @Test
    void harshBrakingIsRaisedOnlyForReadingsCloseTogether() {
        VehicleState state = apply(null, 0, 0, 0, 60, true);
        VehicleStateProcessor.Update update = VehicleStateProcessor.update(state, reading(2, 30, 0, 20, true), SETTINGS);
        assertEquals(VehicleStateProcessor.Finding.Kind.HARSH_BRAKING, update.findings().getFirst().kind());   // -5.6 m/s²

        VehicleState slow = apply(null, 0, 0, 0, 60, true);
        assertTrue(VehicleStateProcessor.update(slow, reading(60, 600, 0, 0, false), SETTINGS).findings().isEmpty());
    }

    private static VehicleState apply(VehicleState state, long second, double east, double north, int speed, boolean moving) {
        return VehicleStateProcessor.update(state, reading(second, east, north, speed, moving), SETTINGS).state();
    }

    private static VehicleReading reading(long second, double east, double north, int speed, boolean moving) {
        double[] p = point(east, north);
        return new VehicleReading("I", "XX01", "t", at(second), p[0], p[1], speed, moving, true, 0,
                DistanceSource.GPS, null, null, 0, false);
    }
}
