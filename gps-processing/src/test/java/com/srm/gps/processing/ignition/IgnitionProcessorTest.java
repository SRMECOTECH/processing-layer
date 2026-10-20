package com.srm.gps.processing.ignition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.srm.gps.processing.TestTrack.at;
import static com.srm.gps.processing.TestTrack.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnitionProcessorTest {

    private static final IgnitionSettings SETTINGS = IgnitionSettings.defaults();

    @Test
    void deviceIgnitionEdgesAreRaisedAtOnce() {
        Run run = new Run();
        run.apply(60, 30, 400, true);
        run.apply(60, 0, 0, false);
        run.apply(60, 0, 0, true);
        assertEquals(List.of(IgnitionFinding.Kind.IGNITION_OFF, IgnitionFinding.Kind.IGNITION_ON),
                run.findings.stream().map(IgnitionFinding::kind).toList());
        assertEquals("DEVICE", run.state.source());
    }

    @Test
    void withoutAWireStandingFiveMinutesMeansEngineOffBackDatedToTheStop() {
        Run run = new Run();
        run.apply(60, 40, 600, null);
        run.apply(60, 40, 600, null);
        for (int i = 0; i < 8; i++) {
            run.apply(60, 0, 0, null);   // standing from second 180
        }
        IgnitionFinding off = run.findings.stream().filter(f -> f.kind() == IgnitionFinding.Kind.IGNITION_OFF).findFirst().orElseThrow();
        assertEquals("VIRTUAL", off.source());
        assertEquals(at(180), off.startedAt());
    }

    @Test
    void engineSecondsCountOnlyWhileOnAtBothEnds() {
        Run run = new Run();
        run.apply(60, 30, 400, true);
        run.apply(60, 30, 400, true);
        run.apply(60, 30, 400, true);
        run.apply(60, 0, 0, false);
        assertEquals(120, run.state.engineSeconds());
    }

    @Test
    void idlingIsRaisedAfterFiveMinutesAndClosedWithItsDuration() {
        Run run = new Run();
        run.apply(60, 30, 400, true);
        for (int i = 0; i < 7; i++) {
            run.apply(60, 1, 0, true);   // engine on, standing, seconds 120..480
        }
        run.apply(60, 30, 400, true);
        IgnitionFinding started = run.findings.stream().filter(f -> f.kind() == IgnitionFinding.Kind.IDLING_STARTED).findFirst().orElseThrow();
        IgnitionFinding ended = run.findings.stream().filter(f -> f.kind() == IgnitionFinding.Kind.IDLING_ENDED).findFirst().orElseThrow();
        assertEquals(at(120), started.startedAt());
        assertEquals(360, ended.durationSeconds());   // 120 to 480
        assertTrue(run.state.idleSince() == null);
    }

    private static final class Run {
        IgnitionState state = IgnitionState.initial();
        final List<IgnitionFinding> findings = new ArrayList<>();
        long second;
        double east;

        void apply(long every, int speed, double meters, Boolean ignition) {
            second += every;
            east += meters;
            double[] p = point(east, 0);
            IgnitionProcessor.Update update = IgnitionProcessor.update(state,
                    new IgnitionReading(at(second), p[0], p[1], speed, meters, ignition, false), SETTINGS);
            state = update.state();
            findings.addAll(update.findings());
        }
    }
}
