package com.srm.gps.processing.stoppage;

import com.srm.gps.processing.stoppage.StoppageState.Kind;
import com.srm.gps.processing.stoppage.StoppageState.Phase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.srm.gps.processing.TestTrack.at;
import static com.srm.gps.processing.TestTrack.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoppageDetectorTest {

    private static final StoppageSettings SETTINGS = StoppageSettings.defaults();   // 100 m, 180 s, 3 km/h

    @Test
    void standingWithTheEngineOnBecomesAStoppageBackDatedToArrival() {
        Run run = new Run();
        run.drive(60, 3, 40, true);
        run.stand(60, 5, true);           // 5 minutes, engine running
        StopFinding started = run.findings.getFirst();
        assertEquals(Kind.STOPPAGE, started.kind());
        assertTrue(started.started());
        assertEquals(at(240), started.startedAt());   // first standing reading
        assertEquals(Phase.STOPPED, run.state.phase());
    }

    @Test
    void standingWithTheEngineOffIsParkedAndNotAnAlarm() {
        Run run = new Run();
        run.drive(60, 3, 40, true);
        run.stand(60, 5, false);
        assertEquals(Kind.PARKED, run.findings.getFirst().kind());
    }

    @Test
    void aShortHaltIsNotAStop() {
        Run run = new Run();
        run.drive(60, 3, 40, true);
        run.stand(60, 2, true);           // 1 minute at the lights
        run.drive(60, 3, 40, true);
        assertTrue(run.findings.isEmpty());
    }

    @Test
    void driving_offEndsTheStopWithItsDuration() {
        Run run = new Run();
        run.drive(60, 2, 40, true);
        run.stand(60, 10, true);
        run.drive(60, 3, 40, true);
        StopFinding ended = run.findings.stream().filter(f -> !f.started()).findFirst().orElseThrow();
        assertEquals(Kind.STOPPAGE, ended.kind());
        assertEquals(540, ended.durationSeconds());   // first to last standing reading: 9 minutes
        assertEquals(Phase.MOVING, run.state.phase());
    }

    @Test
    void switchingTheEngineOffDuringAStoppageNeutralisesTheRest() {
        Run run = new Run();
        run.drive(60, 2, 40, true);
        run.stand(60, 5, true);
        run.stand(60, 3, false);
        assertEquals(List.of(Kind.STOPPAGE, Kind.STOPPAGE, Kind.PARKED), run.findings.stream().map(StopFinding::kind).toList());
        assertFalse(run.findings.get(1).started());
        assertTrue(run.findings.get(2).started());
    }

    @Test
    void aSpeedometerStuckAtZeroCannotHideAMovingTruck() {
        Run run = new Run();
        run.driveReportingZero(60, 6, 40);   // really 40 km/h, speedometer says 0
        assertTrue(run.findings.isEmpty());
    }

    private static final class Run {
        StoppageState state = StoppageState.initial();
        final List<StopFinding> findings = new ArrayList<>();
        long second;
        double east;

        void drive(long every, int readings, int kmph, boolean ignition) {
            for (int i = 0; i < readings; i++) {
                second += every;
                east += kmph / 3.6 * every;
                apply(kmph, ignition, 0);
            }
        }

        void driveReportingZero(long every, int readings, int kmph) {
            for (int i = 0; i < readings; i++) {
                second += every;
                east += kmph / 3.6 * every;
                apply(0, true, 0);
            }
        }

        /** Standing: a few metres of GPS wander, speed 0. */
        void stand(long every, int readings, boolean ignition) {
            for (int i = 0; i < readings; i++) {
                second += every;
                apply(0, ignition, (i % 2 == 0 ? 4 : -4));
            }
        }

        private void apply(int speed, boolean ignition, double wander) {
            double[] p = point(east + wander, wander);
            StoppageDetector.Update update = StoppageDetector.update(state,
                    new StoppageReading(at(second), p[0], p[1], speed, ignition, "DEVICE", false), SETTINGS);
            state = update.state();
            findings.addAll(update.findings());
        }
    }
}
