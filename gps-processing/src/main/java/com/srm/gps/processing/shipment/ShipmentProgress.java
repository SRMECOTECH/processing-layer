package com.srm.gps.processing.shipment;

import java.time.Instant;

/**
 * What the GPS has proved about one shipment so far, and how one more reading changes it. Pure function, no I/O.
 *
 * Every moment is the EARLIEST reading that proves it, so the result does not depend on the order readings arrive in:
 * a reading delivered twice changes nothing, a late reading from the device's memory can only move a moment earlier,
 * and a replay of the whole trip ends where the live run ended.
 *
 *   departure  first reading after the gate-out (dispatch) outside the origin fence. When the destination lies inside
 *              the origin fence ("intra_fence": Jamshedpur plant -> Adityapur), or there is no origin fence, the first
 *              moving reading after the gate-out instead - such a truck never leaves the origin fence.
 *   arrival    first reading inside the destination fence that comes after a reading outside it, so a trip that
 *              starts inside its destination fence (Jamshedpur -> Jamshedpur) is not "arrived" at the gate-out.
 *   last       the newest reading: where the truck is now.
 *
 * @param leftOriginAt          departure, or null
 * @param outsideDestinationAt  first reading after dispatch outside the destination fence, or null
 * @param arrivedAt             arrival, or null
 * @param lastReadingAt         newest reading, or null
 */
public record ShipmentProgress(Instant leftOriginAt, Instant outsideDestinationAt, Instant arrivedAt,
                               Instant lastReadingAt, Double lastLatitude, Double lastLongitude, Integer lastSpeedKmph) {

    public static final ShipmentProgress NONE = new ShipmentProgress(null, null, null, null, null, null, null);

    /** Speed at which a truck without an origin fence counts as having moved off. */
    static final int MOVING_SPEED_KMPH = 5;

    /** One processed reading of the shipment's truck. */
    public record Reading(Instant recordedAt, double latitude, double longitude, int speedKmph, boolean moving) {
    }

    /**
     * @param dispatchedAt  the gate-out: readings before it belong to the time in the plant, not to the trip
     * @param origin        the origin fence, or null when the shipment's origin is not a known place
     * @param destination   the destination fence, or null
     */
    public static ShipmentProgress apply(ShipmentProgress before, Instant dispatchedAt, Fence origin, Fence destination, Reading r) {
        if (r.recordedAt().isBefore(dispatchedAt)) {
            return before;
        }
        Instant t = r.recordedAt();
        boolean departureByFence = origin != null && !origin.containsCentreOf(destination);
        boolean departed = departureByFence
                ? !origin.contains(r.latitude(), r.longitude())
                : r.moving() || r.speedKmph() >= MOVING_SPEED_KMPH;

        Instant leftOrigin = departed ? earliest(before.leftOriginAt, t) : before.leftOriginAt;
        Instant outsideDestination = before.outsideDestinationAt;
        Instant arrived = before.arrivedAt;
        if (destination != null) {
            if (destination.contains(r.latitude(), r.longitude())) {
                if (outsideDestination != null && outsideDestination.isBefore(t)) {
                    arrived = earliest(arrived, t);
                }
            } else {
                outsideDestination = earliest(outsideDestination, t);
            }
        }

        boolean newest = before.lastReadingAt == null || t.isAfter(before.lastReadingAt);
        return new ShipmentProgress(leftOrigin, outsideDestination, arrived,
                newest ? t : before.lastReadingAt,
                newest ? Double.valueOf(r.latitude()) : before.lastLatitude,
                newest ? Double.valueOf(r.longitude()) : before.lastLongitude,
                newest ? Integer.valueOf(r.speedKmph()) : before.lastSpeedKmph);
    }

    private static Instant earliest(Instant current, Instant candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }
}
