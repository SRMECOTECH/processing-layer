package com.srm.gps.processing.geofence;

import com.srm.gps.processing.geofence.GeofenceFinding.Kind;
import com.srm.gps.processing.rule.DistanceCalculator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeofenceTrackerTest {

    private static final Geofence PLANT = Geofence.circle(1, "PLANT", "Plant", "ORIGIN", 22.80, 86.20, 3_000, 120, null);
    private static final Geofence GATE = Geofence.circle(2, "GATE", "Gate yard", "YARD", 22.80, 86.20, 500, null, null);
    private static final Geofence TOWN = Geofence.circle(3, "TOWN", "Town", "DESTINATION", 22.80, 86.50, 1_000, null, null);
    private static final GeofenceIndex INDEX = GeofenceIndex.of(List.of(PLANT, GATE, TOWN));
    private static final GeofenceSettings SETTINGS = GeofenceSettings.defaults();
    private static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");

    /** Feeds readings one minute apart and collects what was found. */
    private static final class Drive {
        GeofenceTrackState state = GeofenceTrackState.EMPTY;
        final List<GeofenceFinding> found = new ArrayList<>();
        Instant at = T0;
        Instant previous;

        Drive at(double lat, double lon) {
            return after(60, lat, lon);
        }

        Drive after(long seconds, double lat, double lon) {
            at = previous == null ? at : at.plusSeconds(seconds);
            long since = previous == null ? 0 : Duration.between(previous, at).getSeconds();
            GeofenceTracker.Update u = GeofenceTracker.update(state, new GeofenceReading(at, lat, lon, 30, since, false), INDEX, SETTINGS);
            state = u.state();
            found.addAll(u.findings());
            previous = at;
            return this;
        }

        Drive stay(int minutes, double lat, double lon) {
            for (int i = 0; i < minutes; i++) {
                at(lat, lon);
            }
            return this;
        }

        List<Kind> kinds(Geofence fence) {
            return found.stream().filter(f -> f.fence().id() == fence.id()).map(GeofenceFinding::kind).toList();
        }
    }

    private static double east(double meters) {
        return 86.20 + DistanceCalculator.longitudeDelta(meters, 22.80);
    }

    @Test
    void anEntryIsBelievedAfterFifteenMinutesAndStampedAtTheFirstReadingInside() {
        Drive d = new Drive().stay(5, 22.80, east(8_000)).stay(20, 22.80, east(1_000));
        assertEquals(List.of(Kind.ENTERED), d.kinds(PLANT));
        GeofenceFinding entry = d.found.getFirst();
        assertEquals(T0.plus(Duration.ofMinutes(5)), entry.at(), "the first reading inside, not when it was believed");
        assertEquals(60, entry.gapSeconds());
        assertTrue(d.kinds(GATE).isEmpty(), "1 km from the centre is outside the 500 m gate yard");
    }

    @Test
    void scatterAtTheBorderRaisesNothing() {
        // a truck parked on the 3 km border: in, out, in, out every minute for an hour
        Drive d = new Drive().at(22.80, east(8_000));
        for (int i = 0; i < 60; i++) {
            d.at(22.80, east(i % 2 == 0 ? 2_980 : 3_020));
        }
        assertTrue(d.found.isEmpty(), "found " + d.found);
    }

    @Test
    void anExitIsStampedAtTheLastReadingInsideAndCarriesTheDwell() {
        Drive d = new Drive().stay(30, 22.80, east(1_000)).stay(20, 22.80, east(4_000));
        assertEquals(List.of(Kind.ENTERED, Kind.EXITED), d.kinds(PLANT));
        GeofenceFinding exit = d.found.stream().filter(f -> f.kind() == Kind.EXITED).findFirst().orElseThrow();
        assertEquals(T0.plus(Duration.ofMinutes(29)), exit.at());
        assertEquals(29 * 60, exit.dwellSeconds());
        assertEquals("HELD", exit.reason());
        assertTrue(d.state.tracks().isEmpty(), "nothing kept once outside");
    }

    @Test
    void farOutIsAnExitAtOnceEvenWhenTheGpsThenDies() {
        Drive d = new Drive().stay(30, 22.80, east(1_000)).at(22.80, east(7_000));
        assertEquals(List.of(Kind.ENTERED, Kind.EXITED), d.kinds(PLANT));
        assertEquals("ESCAPED", d.found.getLast().reason());
    }

    @Test
    void aDriveThroughIsAPassNotAVisit() {
        // 6 minutes inside the 1 km town fence while driving through it
        Drive d = new Drive().at(22.80, 86.47);
        for (int i = 0; i <= 12; i++) {
            d.at(22.80, 86.485 + i * 0.0025);
        }
        d.stay(3, 22.80, 86.54);
        assertEquals(List.of(Kind.PASSED), d.kinds(TOWN));
        assertTrue(!d.found.getFirst().inferred());
    }

    @Test
    void aFenceJumpedBetweenTwoReadingsIsAnInferredPass() {
        // 2 km between readings two minutes apart, straight over the 1 km town fence's centre, with no reading inside it
        Drive d = new Drive().at(22.80, 86.49).after(120, 22.80, 86.51);
        assertEquals(List.of(Kind.PASSED), d.kinds(TOWN));
        GeofenceFinding pass = d.found.getFirst();
        assertTrue(pass.inferred());
        assertEquals(T0.plusSeconds(60), pass.at(), "half-way through the two-minute move");
    }

    @Test
    void nestedFencesAreEnteredTogetherAndADwellAlertIsRaisedOnce() {
        // the plant's own dwell limit is 120 min
        Drive d = new Drive().at(22.80, east(8_000)).stay(200, 22.80, 86.20);
        assertEquals(List.of(Kind.ENTERED, Kind.DWELL_EXCEEDED), d.kinds(PLANT));
        assertEquals(List.of(Kind.ENTERED), d.kinds(GATE), "the yard's limit (240 min) is not reached");
        GeofenceFinding dwell = d.found.stream().filter(f -> f.kind() == Kind.DWELL_EXCEEDED).findFirst().orElseThrow();
        assertEquals(dwell.enteredAt().plus(Duration.ofMinutes(120)), dwell.at());
        d.stay(20, 22.80, east(8_000));
        GeofenceFinding exit = d.found.stream().filter(f -> f.kind() == Kind.EXITED && f.fence().id() == PLANT.id()).findFirst().orElseThrow();
        assertEquals(dwell.at(), exit.dwellAlertAt(), "the exit closes the dwell episode it opened");
    }

    @Test
    void redeliveredAndLateReadingsChangeNothing() {
        Drive d = new Drive().stay(20, 22.80, 86.20);
        GeofenceTrackState before = d.state;
        GeofenceTracker.Update again = GeofenceTracker.update(before, new GeofenceReading(d.at, 22.80, east(9_000), 30, 60, false), INDEX, SETTINGS);
        assertEquals(before, again.state());
        assertTrue(again.findings().isEmpty());
        GeofenceTracker.Update late = GeofenceTracker.update(before,
                new GeofenceReading(d.at.plusSeconds(30), 22.80, east(9_000), 30, 60, true), INDEX, SETTINGS);
        assertEquals(before, late.state());
    }

    @Test
    void theStateSurvivesARestartThroughItsRedisFields() {
        Drive d = new Drive().stay(20, 22.80, 86.20).stay(3, 22.80, east(3_500));
        assertTrue(d.state.tracks().get(PLANT.id()).pending() != null, "an exit is pending");
        GeofenceTrackState restored = GeofenceTrackState.fromFields(d.state.toFields());
        assertEquals(d.state, restored);
        d.state = restored;
        d.stay(20, 22.80, east(3_500));
        assertEquals(List.of(Kind.ENTERED, Kind.EXITED), d.kinds(PLANT));
        assertNull(d.state.tracks().get(PLANT.id()));
    }

    @Test
    void onlyChangesToTheFencesNeedWriting() {
        Drive d = new Drive().at(22.0, 85.0);
        GeofenceTracker.Update highway = GeofenceTracker.update(d.state,
                new GeofenceReading(d.at.plusSeconds(60), 22.0, 85.01, 40, 60, false), INDEX, SETTINGS);
        assertTrue(!highway.durableChange(), "a reading on the highway changes no fence");
        GeofenceTracker.Update nearPlant = GeofenceTracker.update(highway.state(),
                new GeofenceReading(d.at.plusSeconds(120), 22.80, 86.20, 40, 60, false), INDEX, SETTINGS);
        assertTrue(nearPlant.durableChange(), "a pending entry is written");
    }
}
