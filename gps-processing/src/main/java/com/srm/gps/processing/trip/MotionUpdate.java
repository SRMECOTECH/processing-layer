package com.srm.gps.processing.trip;

import java.util.List;

/**
 * The result of one reading: the new state, plus the trips and stops to save.
 *
 * @param state   the state to store (unchanged when the reading was skipped)
 * @param applied false when the reading was not newer than the last one (late, or delivered again) and was skipped
 * @param events  MOVING / STOPPED events, in time order
 * @param trips   trips that changed: closed ones and the open one (its end moved). Save by (imei, start).
 * @param stops   stops that changed, same rule as trips
 */
public record MotionUpdate(
        MotionState state,
        boolean applied,
        List<MotionEvent> events,
        List<DetectedTrip> trips,
        List<DetectedStop> stops) {

    public MotionUpdate {
        events = List.copyOf(events);
        trips = List.copyOf(trips);
        stops = List.copyOf(stops);
    }

    static MotionUpdate skipped(MotionState unchanged) {
        return new MotionUpdate(unchanged, false, List.of(), List.of(), List.of());
    }
}
