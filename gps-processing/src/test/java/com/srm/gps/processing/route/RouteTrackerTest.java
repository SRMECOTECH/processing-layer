package com.srm.gps.processing.route;

import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.rule.DistanceCalculator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTrackerTest {

    static final double LAT = 22.80;
    static final double LON0 = 86.20;
    static final RouteSettings CFG = RouteSettings.defaults();
    static final Instant T0 = Instant.parse("2026-11-02T04:00:00Z");

    /** km east of the start, as longitude */
    static double east(double km) {
        return LON0 + km / (111.32 * Math.cos(Math.toRadians(LAT)));
    }

    /** metres north of the line, as latitude */
    static double north(double meters) {
        return LAT + meters / 111_320.0;
    }

    /** 30 km due east, planned at 50 km/h (2,160 s): a highway, corridor 150 m. */
    static RoutePlan plan(List<RoutePlan.Stop> stops) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            points.add(new double[] {LAT, east(i * 0.5)});
        }
        Geofence origin = Geofence.circle(1, "O", "Origin", "ORIGIN", LAT, east(0), 300, null, null);
        Geofence destination = Geofence.circle(2, "D", "Destination", "DESTINATION", LAT, east(30), 300, null, null);
        return RoutePlan.of(7, 1, points, List.of(new RoutePlan.Timing(0, 60, 2_160)), 2_160, stops, origin, destination, 0, 1.0, CFG);
    }

    static final RoutePlan PLAN = plan(List.of());

    /** Feeds readings one minute apart (or as told) and collects what was found. */
    static final class Drive {
        RoutePlan plan = PLAN;
        RouteTrackState state;
        final List<RouteFinding> found = new ArrayList<>();
        Instant at = T0;
        boolean first = true;
        RouteTracker.CheckRequest asked;

        Drive at(double km, double northMeters) {
            return after(60, km, northMeters, 50);
        }

        Drive after(long seconds, double km, double northMeters, int speed) {
            at = first ? at : at.plusSeconds(seconds);
            first = false;
            RouteTracker.Update u = RouteTracker.update(state, new RouteTracker.Reading(at, north(northMeters), east(km), speed, speed > 0),
                    plan, CFG);
            state = u.state();
            found.addAll(u.findings());
            if (u.checkWanted()) {
                asked = RouteTracker.checkRequest(state, plan);
            }
            return this;
        }

        Drive answer(double remainingSeconds, double remainingMeters) {
            RouteTracker.Check c = RouteTracker.judge(asked, remainingSeconds, remainingMeters, CFG);
            RouteTracker.Update u = RouteTracker.applyCheck(state, c, CFG);
            state = u.state();
            found.addAll(u.findings());
            asked = null;
            return this;
        }

        List<RouteFinding.Kind> kinds() {
            return found.stream().map(RouteFinding::kind).toList();
        }
    }

    static Drive onRouteTo(double km) {
        Drive d = new Drive();
        for (double x = 0; x <= km + 1e-9; x += 0.8) {
            d.at(x, 0);
        }
        return d;
    }

    static void between(double value, double low, double high) {
        assertTrue(value >= low && value <= high, value + " not in [" + low + ", " + high + "]");
    }

    static Drive detourOffTheLine() {
        Drive d = onRouteTo(5);
        for (double km = 5.6; km <= 9.6; km += 0.8) {
            d.at(km, 1_200);
        }
        return d;
    }

    @Test
    void drivingTheRouteRaisesNothingAndKeepsTheEtaSteady() {
        Drive d = onRouteTo(20);
        assertEquals(List.of(), d.found);
        assertEquals(RouteTrackState.Status.ON_ROUTE, d.state.status);
        assertEquals(20_000, d.state.chainage, 900);
        assertNotNull(d.state.departedAt);
        // 48 km/h against a 50 km/h plan: the pace learns about 1.04
        between(d.state.pace, 1.0, 1.1);
        between(Duration.between(T0, d.state.etaShown).toMinutes(), 34, 42);
    }

    @Test
    void insideTheOriginNothingStarts() {
        Drive d = new Drive();
        d.at(0, 0).at(0.1, 50).at(0.15, -80);
        assertEquals(RouteTrackState.Status.WAITING, d.state.status);
        assertNull(d.state.etaPromised);
        d.at(1.0, 0);
        assertEquals(RouteTrackState.Status.ON_ROUTE, d.state.status);
        assertNotNull(d.state.etaPromised);
    }

    @Test
    void leavingTheOriginByAnotherRoadIsDepartingOffRoute() {
        Drive d = new Drive();
        d.at(0, 0).at(0.1, 50);
        for (double km = 0.8; km <= 5.6; km += 0.8) {
            d.at(km, 1_500);
        }
        assertEquals(RouteTrackState.Status.SUSPECT, d.state.status);
        assertNotNull(d.state.departedAt);
        assertNotNull(d.asked, "checked like any detour");
    }

    @Test
    void aTruckNeverAtTheStartOfAPlanWaits() {
        Drive d = new Drive();
        for (double km = 10; km <= 14; km += 0.8) {
            d.at(km, 5_000);
        }
        assertEquals(RouteTrackState.Status.WAITING, d.state.status);
        assertNull(d.asked);
    }

    @Test
    void driftAndAParallelLaneAreForgiven() {
        Drive d = onRouteTo(5);
        d.at(5.8, 300).at(6.6, 0).at(7.4, 0);
        d.at(8.2, 220).at(9.0, 220).at(9.8, 10).at(10.6, 0);
        assertEquals(List.of(), d.found);
        assertNull(d.asked);
        assertEquals(RouteTrackState.Status.ON_ROUTE, d.state.status);
    }

    @Test
    void aRealDetourIsCheckedOpenedAndClosedWithItsExtraKilometres() {
        Drive d = detourOffTheLine();
        assertEquals(RouteTrackState.Status.SUSPECT, d.state.status);
        assertNotNull(d.asked, "3 readings, 5 min and 1 km off the line");
        // from here the engine needs 50 min for the 22 km left: far worse than the 25 min the plan had left
        d.answer(3_000, 22_000);
        assertEquals(List.of(RouteFinding.Kind.DEVIATION_OPENED), d.kinds());
        assertEquals(RouteTrackState.Status.OFF_ROUTE, d.state.status);
        between(d.state.addedSeconds / 60.0, 20, 35);
        assertEquals("MEDIUM", RouteTracker.severity(d.state.addedSeconds, d.state.verdict, CFG));
        d.at(10.4, 0).at(11.2, 0).at(12.0, 0);
        assertEquals(List.of(RouteFinding.Kind.DEVIATION_OPENED, RouteFinding.Kind.DEVIATION_CLOSED), d.kinds());
        RouteFinding closed = d.found.get(1);
        assertEquals(d.found.get(0).startedAt(), closed.startedAt(), "one episode: the close updates the same event");
        assertTrue((double) closed.details().get("kmOff") > 3.0);
        assertEquals(RouteTrackState.Status.ON_ROUTE, d.state.status);
        assertEquals(1, d.state.deviations);
    }

    @Test
    void aMuchSlowerWayIsCritical() {
        Drive d = detourOffTheLine();
        d.answer(9_000, 60_000);
        assertEquals("CRITICAL", RouteTracker.severity(d.state.addedSeconds, d.state.verdict, CFG));
    }

    @Test
    void anEngineThatCannotAnswerOpensAnUnverifiedDeviation() {
        Drive d = detourOffTheLine();
        RouteTracker.Update u = RouteTracker.applyCheck(d.state, RouteTracker.unavailable(d.asked), CFG);
        assertEquals(RouteTrackState.Status.OFF_ROUTE, u.state().status);
        assertEquals("UNVERIFIED", u.state().verdict);
        assertEquals("MEDIUM", RouteTracker.severity(u.state().addedSeconds, u.state().verdict, CFG));
    }

    @Test
    void aRoadAboutAsGoodIsAnAlternateNotAnAlarm() {
        Drive d = detourOffTheLine();
        // the plan had 1,800 s left from km 5; 300 s driven + 1,650 s from here = within 10 %
        d.answer(1_650, 21_000);
        assertEquals(List.of(RouteFinding.Kind.ALTERNATE_TAKEN), d.kinds());
        assertEquals(RouteTrackState.Status.ON_ROUTE, d.state.status);
        assertEquals(1, d.state.alternates);
    }

    @Test
    void anAnswerForAnEpisodeThatEndedChangesNothing() {
        Drive d = detourOffTheLine();
        RouteTracker.CheckRequest old = d.asked;
        d.at(10.4, 0).at(11.2, 0);
        assertEquals(RouteTrackState.Status.ON_ROUTE, d.state.status);
        RouteTracker.Update u = RouteTracker.applyCheck(d.state, RouteTracker.judge(old, 9_000, 60_000, CFG), CFG);
        assertEquals(List.of(), u.findings());
        assertEquals(RouteTrackState.Status.ON_ROUTE, u.state().status);
    }

    @Test
    void farOffIsCheckedAtOnce() {
        Drive d = onRouteTo(5);
        d.after(240, 5.5, 2_500, 50);
        assertNotNull(d.asked);
    }

    @Test
    void drivingBackAlongTheRouteIsWrongDirectionAndForwardAgainClosesIt() {
        Drive d = onRouteTo(10);
        d.at(9.4, 0).at(8.8, 0).at(8.2, 0).at(7.6, 0);
        assertEquals(List.of(RouteFinding.Kind.WRONG_WAY_OPENED), d.kinds());
        d.at(8.2, 0).at(8.8, 0).at(9.4, 0);
        assertEquals(List.of(RouteFinding.Kind.WRONG_WAY_OPENED, RouteFinding.Kind.WRONG_WAY_CLOSED), d.kinds());
        assertTrue((double) d.found.get(1).details().get("backKm") > 1.5);
    }

    @Test
    void aShortUTurnAtAPlazaIsNotWrongDirection() {
        Drive d = onRouteTo(10);
        d.at(9.7, 0).at(10.4, 0).at(11.2, 0);
        assertEquals(List.of(), d.found);
    }

    @Test
    void aGpsJumpIsIgnoredButThreeInARowAreBelieved() {
        Drive d = onRouteTo(5);
        Instant last = d.state.lastAt;
        d.after(60, 60, 30_000, 50);
        assertEquals(last, d.state.lastAt, "the jump is not applied");
        assertEquals(List.of(), d.found);
        d.after(60, 5.6, 0, 50);
        assertEquals(0, d.state.jumps, "back where it was: the jump was a glitch");
        d.after(60, 60, 30_000, 50).after(60, 60.5, 30_000, 50).after(60, 61, 30_000, 50);
        assertEquals(d.at, d.state.lastAt, "the third jump in a row moves the truck there");
    }

    @Test
    void reachingTheDestinationClosesEverythingAndComparesWithThePromise() {
        Drive d = onRouteTo(29.6);
        d.at(30, 0);
        assertEquals(List.of(RouteFinding.Kind.ARRIVED), d.kinds());
        assertEquals(RouteTrackState.Status.ARRIVED, d.state.status);
        assertTrue(d.found.get(0).details().keySet().containsAll(List.of("lateMinutes", "promisedEta", "tripMinutes")));
        assertTrue(Math.abs((long) d.found.get(0).details().get("lateMinutes")) < 10);
    }

    @Test
    void aLongStopMovesTheEtaAndAnnouncesIt() {
        Drive d = onRouteTo(10);
        Instant before = d.state.etaShown;
        for (int i = 0; i < 12; i++) {
            d.after(600, 10.4, 0, 0);
        }
        assertTrue(d.kinds().contains(RouteFinding.Kind.ETA_CHANGED));
        assertTrue(Duration.between(before, d.state.etaShown).toMinutes() > 60);
        RouteFinding change = d.found.stream().filter(f -> f.kind() == RouteFinding.Kind.ETA_CHANGED).findFirst().orElseThrow();
        assertEquals("STOPPED", change.details().get("reason"));
    }

    @Test
    void driverRestsAreAddedForLongDrivesAhead() {
        // a 1,000 km line at 50 km/h: 20 h of driving needs four rests of 30 min
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 1_000; i++) {
            points.add(new double[] {LAT, east(i)});
        }
        RoutePlan longPlan = RoutePlan.of(8, 1, points, List.of(), 72_000, List.of(),
                null, Geofence.circle(3, "D", "D", "DESTINATION", LAT, east(1_000), 300, null, null), 0, 1.0, CFG);
        Drive d = new Drive();
        d.plan = longPlan;
        d.at(1, 0).at(1.8, 0);
        between(Duration.between(d.at, d.state.etaShown).toMinutes(), 20 * 60 + 4 * 30 - 10, 20 * 60 + 4 * 30 + 10);
    }

    @Test
    void aSlowStartDoesNotSwingALongTripsEta() {
        // 1,000 km planned at 50 km/h; the first 40 minutes crawl at half speed out of town
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 1_000; i++) {
            points.add(new double[] {LAT, east(i)});
        }
        RoutePlan longPlan = RoutePlan.of(10, 1, points, List.of(), 72_000, List.of(),
                null, Geofence.circle(5, "D", "D", "DESTINATION", LAT, east(1_000), 300, null, null), 0, 1.0, CFG);
        Drive d = new Drive();
        d.plan = longPlan;
        d.at(1, 0);
        Instant promised = d.state.etaPromised;
        double km = 1;
        for (int i = 0; i < 40; i++) {
            km += 0.4;
            d.at(km, 0);
        }
        // the live pace says about 2x; weighed against an hour of the prior and gliding, the ETA moved little
        assertTrue(d.state.pace > 1.5);
        assertTrue(Duration.between(promised, d.state.etaShown).toMinutes() < 90);
        assertTrue(d.found.stream().filter(f -> f.kind() == RouteFinding.Kind.ETA_CHANGED).count() <= 1);
    }

    @Test
    void theStateSurvivesRedis() {
        Drive d = detourOffTheLine();
        d.answer(3_000, 22_000);
        RouteTrackState back = RouteTrackState.fromFields(d.state.toFields());
        assertNotNull(back);
        assertEquals(d.state.toFields(), back.toFields());
        assertNull(RouteTrackState.fromFields(java.util.Map.of("v", "0")));
    }

    @Test
    void onARoadUsedTwiceProgressFollowsTheTrip() {
        // out 10 km and back on the same road
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            points.add(new double[] {LAT, east(i * 0.5)});
        }
        for (int i = 19; i >= 0; i--) {
            points.add(new double[] {LAT, east(i * 0.5)});
        }
        RoutePlan loop = RoutePlan.of(9, 1, points, List.of(), 1_440, List.of(), null,
                Geofence.circle(4, "D", "D", "DESTINATION", north(5_000), east(0), 200, null, null), 0, 1.0, CFG);
        assertEquals(4_000, loop.project(LAT, east(4), 3_000).chainageMeters(), 5);
        assertEquals(16_000, loop.project(LAT, east(4), 15_000).chainageMeters(), 5);
    }

    @Test
    void projectionMatchesABruteForceScan() {
        Random random = new Random(42);
        for (int i = 0; i < 5_000; i++) {
            double lat = LAT + (random.nextDouble() - 0.5) * 0.08;
            double lon = east(-2 + random.nextDouble() * 34);
            RoutePlan.Projection p = PLAN.project(lat, lon, Double.NaN);
            double best = Double.MAX_VALUE;
            for (int s = 0; s < PLAN.size() - 1; s++) {
                best = Math.min(best, DistanceCalculator.distanceToLine(lat, lon,
                        PLAN.latitude(s), PLAN.longitude(s), PLAN.latitude(s + 1), PLAN.longitude(s + 1)));
            }
            assertEquals(best, p.crossTrackMeters(), Math.max(2.0, best * 0.01));
        }
    }

    @Test
    void plannedSecondsFollowTheManeuvers() {
        assertEquals(1_080, PLAN.secondsAt(15_000), 20);
        assertEquals(2_160, PLAN.totalSeconds(), 1e-6);
        assertEquals(150, PLAN.project(LAT, east(3), Double.NaN).corridorMeters(), 1e-9);
    }

    @Test
    void polylineRoundTrip() {
        List<double[]> points = List.of(new double[] {22.804123, 86.202456}, new double[] {22.81, 86.19}, new double[] {13.0827, 80.2707});
        List<double[]> back = Polyline6.decode(Polyline6.encode(points));
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(points.get(i)[0], back.get(i)[0], 1e-6);
            assertEquals(points.get(i)[1], back.get(i)[1], 1e-6);
        }
    }
}
