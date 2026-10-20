package com.srm.gps.processing.speed;

import com.srm.gps.processing.speed.SpeedViolationState.Phase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.srm.gps.processing.TestTrack.at;
import static com.srm.gps.processing.TestTrack.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedViolationProcessorTest {

    private static final SpeedRule RULE = SpeedRule.defaults();   // 60 km/h, 60 s, 3 pings, hysteresis 5

    @Test
    void aShortBurstOverTheLimitIsNotAViolation() {
        Run run = new Run();
        run.drive(0, 30, 50);    // 50 km/h every 30 s
        run.drive(30, 1, 75);    // one reading over
        run.drive(30, 3, 50);
        assertTrue(run.findings.isEmpty());
        assertEquals(Phase.NORMAL, run.state.phase());
    }

    @Test
    void sustainedSpeedingConfirmedByGpsIsRaisedOnceAndClosedWhenSpeedDrops() {
        Run run = new Run();
        run.drive(30, 2, 50);
        run.drive(30, 6, 80);    // 80 km/h for 3 minutes, positions agree
        run.drive(30, 3, 40);
        List<SpeedFinding> confirmed = run.of(SpeedFinding.Kind.VIOLATION_CONFIRMED);
        List<SpeedFinding> ended = run.of(SpeedFinding.Kind.VIOLATION_ENDED);
        assertEquals(1, confirmed.size());
        assertEquals(1, ended.size());
        assertEquals("GPS_VERIFIED", ended.getFirst().verdict());
        assertEquals(at(90), confirmed.getFirst().startedAt());   // back-dated to the first reading over the limit
        assertEquals(80.0, ended.getFirst().details().get("maxSpeedKmph"));
        assertEquals("SPEED_DROPPED", ended.getFirst().details().get("endReason"));
    }

    @Test
    void speedInTheHysteresisBandKeepsTheEpisodeOpen() {
        Run run = new Run();
        run.drive(30, 1, 50);
        run.drive(30, 4, 80);
        run.drive(30, 3, 58);    // between 55 and 60: still the same episode
        assertEquals(1, run.of(SpeedFinding.Kind.VIOLATION_CONFIRMED).size());
        assertEquals(0, run.of(SpeedFinding.Kind.VIOLATION_ENDED).size());
        assertEquals(Phase.VIOLATING, run.state.phase());
    }

    @Test
    void aSpeedometerThatReadsHighWhileTheTruckCrawlsIsFlaggedAsSensorSuspect() {
        Run run = new Run();
        run.driveReporting(30, 6, 30, 85);   // really 30 km/h, speedometer says 85
        run.drive(30, 2, 20);
        SpeedFinding ended = run.of(SpeedFinding.Kind.VIOLATION_ENDED).getFirst();
        assertEquals("SENSOR_SUSPECT", ended.verdict());
        assertTrue(run.of(SpeedFinding.Kind.SENSOR_MISMATCH).size() >= 1);
    }

    @Test
    void aLongDataGapClosesTheEpisode() {
        Run run = new Run();
        run.drive(30, 1, 50);
        run.drive(30, 4, 80);
        run.second += 600;       // 10 minutes of silence
        run.drive(30, 1, 80);
        SpeedFinding ended = run.of(SpeedFinding.Kind.VIOLATION_ENDED).getFirst();
        assertEquals("DATA_GAP", ended.details().get("endReason"));
    }

    @Test
    void aJumpInTheWindowMakesTheGpsSpeedUnverifiableInsteadOfAbsurd() {
        double[] a = point(0, 0);
        double[] b = point(300, 0);
        double[] c = point(9_300, 0);   // 9 km in 30 s: a glitch
        List<SpeedViolationState.WindowPoint> window = List.of(
                new SpeedViolationState.WindowPoint(at(0), a[0], a[1], 30, null),
                new SpeedViolationState.WindowPoint(at(30), b[0], b[1], 30, null),
                new SpeedViolationState.WindowPoint(at(60), c[0], c[1], 30, null));
        assertEquals(null, SpeedViolationProcessor.gpsSpeed(window, 180));
    }

    @Test
    void aLongSilenceCutsTheGpsSpeedWindow() {
        double[] a = point(0, 0);
        double[] b = point(9_000, 0);    // 9 km over a 10-minute gap
        double[] c = point(9_500, 0);    // then 500 m in 60 s = 30 km/h
        List<SpeedViolationState.WindowPoint> window = List.of(
                new SpeedViolationState.WindowPoint(at(0), a[0], a[1], 0, null),
                new SpeedViolationState.WindowPoint(at(600), b[0], b[1], 30, null),
                new SpeedViolationState.WindowPoint(at(660), c[0], c[1], 30, null));
        assertEquals(30, SpeedViolationProcessor.gpsSpeed(window, 180), 0.1);
    }

    @Test
    void readingsOutOfOrderAreIgnored() {
        Run run = new Run();
        run.drive(30, 6, 80);
        SpeedViolationState before = run.state;
        double[] p = point(0, 0);
        SpeedViolationProcessor.Update update = SpeedViolationProcessor.update(run.state,
                new SpeedReading(at(5), p[0], p[1], 120, null, true), RULE);
        assertEquals(before, update.state());
        assertTrue(update.findings().isEmpty());
    }

    /** Drives along a straight line east, one reading every {@code every} seconds. */
    private static final class Run {
        SpeedViolationState state = SpeedViolationState.initial();
        final List<SpeedFinding> findings = new ArrayList<>();
        long second;
        double east;

        void drive(long every, int readings, int kmph) {
            driveReporting(every, readings, kmph, kmph);
        }

        /** Moves at {@code realKmph} but the speedometer reports {@code reportedKmph}. */
        void driveReporting(long every, int readings, int realKmph, int reportedKmph) {
            for (int i = 0; i < readings; i++) {
                second += every;
                east += realKmph / 3.6 * every;
                double[] p = point(east, 0);
                SpeedViolationProcessor.Update update = SpeedViolationProcessor.update(state,
                        new SpeedReading(at(second), p[0], p[1], reportedKmph, null, false), RULE);
                state = update.state();
                findings.addAll(update.findings());
            }
        }

        List<SpeedFinding> of(SpeedFinding.Kind kind) {
            return findings.stream().filter(f -> f.kind() == kind).toList();
        }
    }
}
