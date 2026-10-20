package com.srm.gps.processing.step;

import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.processing.ProcessingResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.srm.gps.processing.TestTrack.at;
import static com.srm.gps.processing.TestTrack.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Traccar DistanceHandler rules as ported in CalculateMovementStep. */
class CalculateMovementStepTest {

    private static final String IMEI = "0861000000000001";
    private final CalculateMovementStep step = new CalculateMovementStep();

    @Test
    void theFirstReadingHasNoDistanceAndStartsTheTotalAtZero() {
        ProcessedGpsPosition p = calculate(frame(0, 0, 30, null), Optional.empty());
        assertEquals(DistanceSource.FIRST_READING, p.distanceSource());
        assertEquals(0, p.distanceFromPreviousMeters());
        assertEquals(0, p.totalDistanceMeters());
        assertNull(p.impliedSpeedKmph());
    }

    @Test
    void aNormalMoveCountsTheGreatCircleDistanceAndAddsItToTheTotal() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(0), start[0], start[1], 1000, null);
        ProcessedGpsPosition p = calculate(frame(60, 500, 30, null), Optional.of(last));
        assertEquals(DistanceSource.GPS, p.distanceSource());
        assertEquals(500, p.distanceFromPreviousMeters(), 0.2);
        assertEquals(1500, p.totalDistanceMeters(), 0.2);
        assertEquals(30, p.impliedSpeedKmph(), 0.1);   // 500 m in 60 s
    }

    @Test
    void parkingJitterIsClampedBackToThePreviousPositionWithNoDistance() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(0), start[0], start[1], 1000, null);
        ProcessedGpsPosition p = calculate(frame(60, 6, 0, null), Optional.of(last));
        assertEquals(DistanceSource.JITTER_FILTERED, p.distanceSource());
        assertEquals(0, p.distanceFromPreviousMeters());
        assertEquals(start[0], p.latitude());
        assertEquals(start[1], p.longitude());
        assertEquals(1000, p.totalDistanceMeters());
    }

    @Test
    void aShortMoveWhileTheSpeedometerSaysMovingIsKept() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(0), start[0], start[1], 0, null);
        ProcessedGpsPosition p = calculate(frame(2, 6, 12, null), Optional.of(last));
        assertEquals(DistanceSource.GPS, p.distanceSource());
        assertEquals(6, p.distanceFromPreviousMeters(), 0.1);
    }

    @Test
    void anImpossibleJumpIsNotCountedButTheNewPositionIsKept() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(0), start[0], start[1], 1000, null);
        ProcessedGpsPosition p = calculate(frame(60, 20_000, 40, null), Optional.of(last));   // 20 km in a minute
        assertEquals(DistanceSource.JUMP_FILTERED, p.distanceSource());
        assertEquals(0, p.distanceFromPreviousMeters());
        assertEquals(1000, p.totalDistanceMeters());
        assertEquals(point(20_000, 0)[1], p.longitude(), 1e-9);
    }

    @Test
    void theDeviceOdometerWinsOverGpsWhenItIsBelievable() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(0), start[0], start[1], 1000, 50_000L);
        ProcessedGpsPosition p = calculate(frame(60, 480, 30, 50_510L), Optional.of(last));   // road was 510 m, chord 480 m
        assertEquals(DistanceSource.DEVICE_ODOMETER, p.distanceSource());
        assertEquals(510, p.distanceFromPreviousMeters());
        assertEquals(1510, p.totalDistanceMeters());
    }

    @Test
    void anOdometerThatWentBackwardsIsIgnored() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(0), start[0], start[1], 0, 50_000L);
        ProcessedGpsPosition p = calculate(frame(60, 480, 30, 10L), Optional.of(last));   // odometer reset
        assertEquals(DistanceSource.GPS, p.distanceSource());
        assertEquals(480, p.distanceFromPreviousMeters(), 0.2);
    }

    @Test
    void aLateReadingIsNeverMeasuredAgainstANewerOne() {
        double[] start = point(0, 0);
        PreviousReading last = new PreviousReading(at(600), start[0], start[1], 4000, null);
        ProcessedGpsPosition p = calculate(frame(300, 1000, 30, null), Optional.of(last));
        assertEquals(DistanceSource.OUT_OF_ORDER, p.distanceSource());
        assertEquals(0, p.distanceFromPreviousMeters());
        assertEquals(4000, p.totalDistanceMeters());
    }

    private ProcessedGpsPosition calculate(RawGpsFrame frame, Optional<PreviousReading> previous) {
        ProcessingResult result = step.calculate(frame, previous, "{}", at(10_000));
        return assertInstanceOf(ProcessingResult.Accepted.class, result).position();
    }

    private static RawGpsFrame frame(long second, double eastMeters, int speed, Long odometer) {
        double[] p = point(eastMeters, 0);
        return new RawGpsFrame(IMEI, at(second), p[0], p[1], speed, null, odometer);
    }
}
