package com.srm.gps.processing.stoppage;

import java.time.Instant;

/**
 * Everything the stoppage detector remembers about one device (Redis gps:state:stoppage:{imei}).
 *
 * @param phase           MOVING, CANDIDATE (standing, not long enough yet) or STOPPED
 * @param kind            while STOPPED: STOPPAGE (engine on: alarm) or PARKED (engine off: neutralised)
 * @param anchorAt        first reading of the candidate / stop
 * @param sumLatitude     with sumLongitude and points: the running centroid of the readings in the stop
 * @param stopStartedAt   start of the current stop event (the anchor, or when the kind last changed)
 * @param lastInsideAt    newest reading inside the stop
 * @param outsideInARow   readings outside the radius in a row
 * @param engineOnSeconds seconds of the stop with the engine running
 * @param lastStop*       the previous stop, for merging
 */
public record StoppageState(
        Phase phase,
        Kind kind,
        Instant lastRecordedAt,
        double lastLatitude,
        double lastLongitude,
        Instant anchorAt,
        double sumLatitude,
        double sumLongitude,
        int points,
        Instant stopStartedAt,
        Instant lastInsideAt,
        int outsideInARow,
        long engineOnSeconds,
        Boolean lastIgnition,
        Instant lastStopEndedAt,
        double lastStopLatitude,
        double lastStopLongitude,
        Kind lastStopKind,
        Instant lastStopStartedAt) {

    public enum Phase { MOVING, CANDIDATE, STOPPED }

    public enum Kind { STOPPAGE, PARKED }

    public static StoppageState initial() {
        return new StoppageState(Phase.MOVING, null, null, 0, 0, null, 0, 0, 0, null, null, 0, 0, null, null, 0, 0, null, null);
    }

    public double centroidLatitude() {
        return points == 0 ? lastLatitude : sumLatitude / points;
    }

    public double centroidLongitude() {
        return points == 0 ? lastLongitude : sumLongitude / points;
    }
}
