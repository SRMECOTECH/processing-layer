package com.srm.gps.processing.route;

import com.srm.gps.processing.geofence.Geofence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.srm.gps.processing.route.RouteTrackerTest.CFG;
import static com.srm.gps.processing.route.RouteTrackerTest.LAT;
import static com.srm.gps.processing.route.RouteTrackerTest.between;
import static com.srm.gps.processing.route.RouteTrackerTest.east;
import static com.srm.gps.processing.route.RouteTrackerTest.north;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointTrackerTest {

    static RoutePlan.Stop stop(int seq, double km) {
        return new RoutePlan.Stop(seq, "Stop " + seq, "MANUAL", null,
                Geofence.circle(100 + seq, "S" + seq, "Stop " + seq, "CHECKPOINT", LAT, east(km), 300, null, null), -1, 20);
    }

    static final RoutePlan PLAN = RouteTrackerTest.plan(List.of(stop(1, 8), stop(2, 16)));

    static final class Drive {
        RoutePlan plan = PLAN;
        WaypointTracker.State state;
        final List<RouteFinding> found = new ArrayList<>();
        Instant at = Instant.parse("2026-11-02T04:00:00Z");

        Drive at(long seconds, double km, double northMeters) {
            at = at.plusSeconds(seconds);
            WaypointTracker.Update u = WaypointTracker.update(state,
                    new RouteTracker.Reading(at, north(northMeters), east(km), 40, true), plan, CFG);
            state = u.state();
            found.addAll(u.findings());
            return this;
        }

        List<String> seen() {
            return found.stream().map(f -> f.kind() + "#" + f.details().get("seq")).toList();
        }
    }

    @Test
    void stopsKnowTheirPlaceAlongTheRoute() {
        between(PLAN.stops().get(0).chainageMeters(), 7_900, 8_100);
        between(PLAN.stops().get(1).chainageMeters(), 15_900, 16_100);
        assertEquals(40 * 60, PLAN.staySecondsAhead(0), 1e-9);
        assertEquals(20 * 60, PLAN.staySecondsAhead(10_000), 1e-9);
    }

    @Test
    void aStopIsArrivedAfterTwoMinutesAndDepartedWithItsStay() {
        Drive d = new Drive();
        d.at(60, 6, 0).at(60, 8, 0).at(300, 8.1, 0).at(900, 8.05, 0).at(60, 9, 0);
        assertEquals(List.of("WAYPOINT_ARRIVED#1", "WAYPOINT_DEPARTED#1"), d.seen());
        assertEquals(20L, d.found.get(1).details().get("stayMinutes"));
        assertEquals(WaypointTracker.Status.DEPARTED, d.state.stops().get(1).status());
    }

    @Test
    void drivingThroughIsAPass() {
        Drive d = new Drive();
        d.at(60, 7.8, 0).at(30, 8.1, 0).at(30, 8.6, 0);
        assertEquals(List.of("WAYPOINT_PASSED#1"), d.seen());
    }

    @Test
    void aStopNeverEnteredIsMissedTwoKilometresPastIt() {
        Drive d = new Drive();
        d.at(60, 6, 0).at(60, 7.5, 900).at(60, 8.5, 900).at(60, 9.5, 0).at(60, 10.5, 0);
        assertEquals(List.of("WAYPOINT_MISSED#1"), d.seen());
    }

    @Test
    void anArrivalBeforeAnEarlierStopIsOutOfOrder() {
        Drive d = new Drive();
        d.plan = RouteTrackerTest.plan(List.of(stop(1, 8), stop(2, 9.5)));
        // stop 2 first, coming round stop 1; stop 1 can still be visited after (it is not 2 km behind yet)
        d.at(60, 7, 900).at(60, 9.5, 0).at(200, 9.55, 0);
        assertEquals(List.of("WAYPOINT_ARRIVED#2"), d.seen());
        assertEquals(true, d.found.get(0).details().get("outOfOrder"));
        d.at(300, 8, 0).at(200, 8.05, 0);
        assertEquals(List.of("WAYPOINT_ARRIVED#2", "WAYPOINT_DEPARTED#2", "WAYPOINT_ARRIVED#1"), d.seen());
    }

    @Test
    void atTheDestinationEveryPendingStopIsMissed() {
        Drive d = new Drive();
        d.at(60, 30, 0);
        assertEquals(List.of("WAYPOINT_MISSED#1", "WAYPOINT_MISSED#2"), d.seen());
    }

    @Test
    void theStateSurvivesRedis() {
        Drive d = new Drive();
        d.at(60, 8, 0).at(300, 8.1, 0);
        assertEquals(d.state, WaypointTracker.State.fromFields(d.state.toFields(), PLAN));
    }
}
