package com.srm.gps.processing.geofence;

import java.time.Duration;
import java.time.Instant;

/**
 * Something the geofence tracker found, before it becomes a visit row and a vehicle event.
 *
 * Times are readings the vehicle was really seen at, never guesses (except an inferred pass): an entry is the FIRST
 * reading inside, an exit the LAST reading inside. The real crossing happened somewhere in the gap to the reading
 * next to it, and that gap is given as {@link #gapSeconds()}: the honest uncertainty of the stamp. One minute is a
 * precise answer; three hours is a GPS outage with a timestamp on it, and reports say so.
 *
 * @param kind               what happened
 * @param fence              where
 * @param enteredAt          start of the visit (ENTERED, EXITED, DWELL_EXCEEDED) or of the pass (PASSED)
 * @param at                 ENTERED: = enteredAt; EXITED: last reading inside; PASSED: first reading inside, or the
 *                           inferred crossing; DWELL_EXCEEDED: when the stay went over the limit
 * @param endedAt            PASSED: last reading inside (= at when inferred); otherwise null
 * @param gapSeconds         ENTERED: gap from the reading before; EXITED: gap to the reading after; else 0
 * @param dwellAlertAt       EXITED: when the visit went over its dwell limit, or null; DWELL_EXCEEDED: = at
 * @param dwellLimitMinutes  DWELL_EXCEEDED: the limit that was passed; otherwise 0
 * @param inferred           PASSED between two readings with neither inside: the time is interpolated along the move
 * @param reason             EXITED: HELD (outside long enough), ESCAPED (clearly far out) or FENCE_REMOVED; else null
 */
public record GeofenceFinding(Kind kind, Geofence fence, Instant enteredAt, Instant at, Instant endedAt, double latitude,
                              double longitude, long gapSeconds, Instant dwellAlertAt, int dwellLimitMinutes,
                              boolean inferred, String reason) {

    public enum Kind {
        /** Inside long enough to be a visit (stamped at the first reading inside). */
        ENTERED,
        /** Outside long enough (or far enough) after a visit (stamped at the last reading inside). */
        EXITED,
        /** Through the fence without stopping long enough to be a visit, or over it between two readings. */
        PASSED,
        /** A visit went over its dwell limit: still inside. */
        DWELL_EXCEEDED
    }

    static GeofenceFinding entered(Geofence fence, Instant at, double lat, double lon, long gapSeconds) {
        return new GeofenceFinding(Kind.ENTERED, fence, at, at, null, lat, lon, gapSeconds, null, 0, false, null);
    }

    static GeofenceFinding exited(Geofence fence, Instant enteredAt, Instant at, double lat, double lon, long gapSeconds,
                                  Instant dwellAlertAt, String reason) {
        return new GeofenceFinding(Kind.EXITED, fence, enteredAt, at, null, lat, lon, gapSeconds, dwellAlertAt, 0, false, reason);
    }

    static GeofenceFinding passed(Geofence fence, Instant from, Instant to, double lat, double lon, long gapSeconds, boolean inferred) {
        return new GeofenceFinding(Kind.PASSED, fence, from, from, to, lat, lon, gapSeconds, null, 0, inferred, null);
    }

    static GeofenceFinding dwellExceeded(Geofence fence, Instant enteredAt, Instant alertAt, double lat, double lon, int limitMinutes) {
        return new GeofenceFinding(Kind.DWELL_EXCEEDED, fence, enteredAt, alertAt, null, lat, lon, 0, alertAt, limitMinutes, false, null);
    }

    /** EXITED: how long the visit lasted; PASSED: how long the vehicle was seen inside; otherwise 0. */
    public long dwellSeconds() {
        return switch (kind) {
            case EXITED -> enteredAt == null ? 0 : Math.max(0, Duration.between(enteredAt, at).getSeconds());
            case PASSED -> endedAt == null ? 0 : Math.max(0, Duration.between(at, endedAt).getSeconds());
            default -> 0;
        };
    }
}
