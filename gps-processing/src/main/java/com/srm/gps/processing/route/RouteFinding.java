package com.srm.gps.processing.route;

import java.time.Instant;
import java.util.Map;

/**
 * Something the route tracker or the waypoint tracker found, for the module to turn into an event.
 *
 * @param startedAt when the episode began (equal to at for a moment)
 * @param at        the reading that decided it
 * @param details   numbers for the event (minutes, metres, ETAs as ISO text)
 */
public record RouteFinding(Kind kind, Instant startedAt, Instant at, double latitude, double longitude, Map<String, Object> details) {

    public enum Kind {
        /** The re-plan says the way it is going is clearly worse: ROUTE_DEVIATION opens. */
        DEVIATION_OPENED,
        /** A later check changed the added time of an open deviation. */
        DEVIATION_UPDATED,
        /** Back on the line (or now on a road as good as the plan): ROUTE_DEVIATION closes. */
        DEVIATION_CLOSED,
        /** Off the line but on a road about as good: ROUTE_ALTERNATE, the re-plan becomes the plan. */
        ALTERNATE_TAKEN,
        WRONG_WAY_OPENED,
        WRONG_WAY_CLOSED,
        ETA_CHANGED,
        ARRIVED,
        // waypoints
        WAYPOINT_ARRIVED,
        WAYPOINT_DEPARTED,
        WAYPOINT_PASSED,
        WAYPOINT_MISSED
    }
}
