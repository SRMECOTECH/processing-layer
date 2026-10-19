package com.srm.gps.processing.trip;

import com.srm.gps.processing.rule.GeoDistanceCalculator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Trip and stop detection, ported from Traccar's NewMotionProcessor (report.trip.newLogic) as one pure function:
 *
 *   updateState(state, reading, settings) -> new state + MOVING/STOPPED events + trips and stops to save
 *
 * No database, no Kafka, no clock: the same input always gives the same output, so a replayed reading
 * produces exactly the same trip rows.
 *
 * The rules, read top to bottom:
 *   0. A reading not newer than the last one applied is skipped (late, or delivered again). Late data never
 *      changes a trip's distance or duration.
 *   1. The first reading of a device (or the first after it moved to another vehicle) only sets the starting point.
 *   2. Another vehicle (device_assignment changed): the open trip and stop are closed, the detection starts again.
 *   3. Silence longer than stopGap: the trip is closed at the last reading BEFORE the silence. If the vehicle
 *      clearly drove during the silence (minDistance at more than minDistance/minDuration), the silence itself
 *      is recorded as stop -> trip -> stop.
 *   4. Stopped -> moving: once the vehicle is minDistance away from where it stopped. The trip start is
 *      back-dated through the window to the reading where it actually started rolling.
 *   5. Moving -> stopped: when no reading of the last minDuration is minDistance away from the current one.
 *      Distance-based, so GPS jitter of a parked truck never starts a trip.
 *
 * Differences from Traccar, on purpose:
 *   - rule 3: Traccar stops the trip at the first reading after the silence, which adds the silence to the trip.
 *   - the per-reading movementStatus (speed / jump) is not used: it is too noisy to decide trips.
 */
public final class TripMotionProcessor {

    private TripMotionProcessor() {
    }

    public static MotionUpdate updateState(MotionState state, TripReading reading, TripDetectionSettings settings) {
        if (!state.neverSeen() && !reading.recordedAt().isAfter(state.lastRecordedAt())) {
            return MotionUpdate.skipped(state);
        }
        Calculation calculation = new Calculation(state, reading, settings);
        calculation.run();
        return calculation.result();
    }

    /** The working copy of the state for one reading. Used once, then thrown away. */
    private static final class Calculation {

        private final TripDetectionSettings settings;
        private final MotionState before;
        private final TripReading reading;
        private final WindowReading current;

        private VehicleAssignment assignment;
        private boolean moving;
        private WindowReading eventReading;
        private final List<WindowReading> window;
        private DetectedTrip openTrip;
        private DetectedStop openStop;

        private final List<MotionEvent> events = new ArrayList<>();
        private final List<DetectedTrip> closedTrips = new ArrayList<>();
        private final List<DetectedStop> closedStops = new ArrayList<>();

        Calculation(MotionState before, TripReading reading, TripDetectionSettings settings) {
            this.settings = settings;
            this.before = before;
            this.reading = reading;
            this.current = reading.toWindowReading();
            this.assignment = before.assignment();
            this.moving = before.moving();
            this.eventReading = before.eventRecordedAt() == null ? null
                    : new WindowReading(before.eventRecordedAt(), before.eventLatitude(), before.eventLongitude(), 0, 0);
            this.window = new ArrayList<>(before.window());
            this.openTrip = before.openTrip();
            this.openStop = before.openStop();
        }

        void run() {
            if (before.neverSeen() || window.isEmpty()) {
                startOver();
                return;
            }
            WindowReading last = window.getLast();

            if (!Objects.equals(assignment, reading.assignment())) {
                closeEverything();
                startOver();
                return;
            }

            long gapMillis = Duration.between(last.recordedAt(), current.recordedAt()).toMillis();
            if (gapMillis > settings.stopGapMillis()) {
                double gapDistance = distance(last, current);
                boolean droveDuringGap = gapDistance >= settings.minDistanceMeters()
                        && gapDistance / gapMillis > settings.minAverageSpeedMetersPerMilli();
                if (droveDuringGap) {
                    recordTripAcrossGap(last);
                    return;
                }
                if (moving) {
                    stopAt(last);
                }
                // The vehicle did not drive during the silence: the reading is judged normally, as a stopped vehicle.
            }

            if (moving) {
                continueTripOrStop();
            } else {
                continueStopOrStartTrip();
            }
        }

        /** Rule 5. */
        private void continueTripOrStop() {
            extendOpenTrip();
            for (int index = window.size() - 1; index >= 0; index--) {
                if (distance(window.get(index), current) >= settings.minDistanceMeters()) {
                    return;
                }
            }
            long windowMillis = Duration.between(window.getFirst().recordedAt(), current.recordedAt()).toMillis();
            if (windowMillis >= settings.minDurationMillis()) {
                stopAt(current);
            }
        }

        /** Rule 4. */
        private void continueStopOrStartTrip() {
            if (distance(eventReading, current) < settings.minDistanceMeters()) {
                if (openStop != null) {
                    openStop = openStop.extendedTo(current);
                }
                return;
            }

            // Walk back while the legs are "driving" legs, but never to or before the reading where the vehicle stopped.
            // The reading just before the current one always counts (Traccar does the same).
            int startIndex = window.size();
            WindowReading next = current;
            for (int index = window.size() - 1; index >= 0; index--) {
                WindowReading candidate = window.get(index);
                boolean atOrBeforeStop = !candidate.recordedAt().isAfter(eventReading.recordedAt());
                long legMillis = Duration.between(candidate.recordedAt(), next.recordedAt()).toMillis();
                boolean legMoving = legMillis > 0
                        && distance(candidate, next) / legMillis > settings.minAverageSpeedMetersPerMilli();
                if (atOrBeforeStop || (next != current && !legMoving)) {
                    break;
                }
                startIndex = index;
                next = candidate;
            }

            List<WindowReading> tripReadings = new ArrayList<>(window.subList(startIndex, window.size()));
            tripReadings.add(current);
            WindowReading start = tripReadings.getFirst();

            if (openStop != null) {
                int readingsMovedToTrip = tripReadings.size() - 2; // the start is shared, the current one was never in the stop
                openStop = openStop.closedAt(start.recordedAt(),
                        start == current ? openStop.readingCount() + 1 : openStop.readingCount() - readingsMovedToTrip);
                closedStops.add(openStop);
                openStop = null;
            }

            openTrip = DetectedTrip.openAt(assignment, start);
            for (WindowReading tripReading : tripReadings.subList(1, tripReadings.size())) {
                openTrip = openTrip.extendedTo(tripReading);
            }
            events.add(MotionEvent.at(MotionEvent.Type.MOVING, start));
            moving = true;
            eventReading = start;
        }

        /** Rule 3, when the vehicle clearly drove while it was silent. */
        private void recordTripAcrossGap(WindowReading last) {
            DetectedTrip gapTrip;
            if (moving && openTrip != null && openTrip.startRecordedAt().equals(last.recordedAt())) {
                // The open trip began at the last reading: it IS the trip across the gap.
                gapTrip = openTrip.extendedTo(current).closed();
                openTrip = null;
            } else {
                if (moving) {
                    // The trip ends at the last reading; no stop in between, it would last 0 seconds.
                    events.add(MotionEvent.at(MotionEvent.Type.STOPPED, last));
                    if (openTrip != null) {
                        closedTrips.add(openTrip.closed());
                        openTrip = null;
                    }
                } else {
                    closeOpenStop();
                }
                events.add(MotionEvent.at(MotionEvent.Type.MOVING, last));
                gapTrip = DetectedTrip.openAt(assignment, last).extendedTo(current).closed();
            }
            closedTrips.add(gapTrip);
            events.add(MotionEvent.at(MotionEvent.Type.STOPPED, current));
            openStop = DetectedStop.openAt(assignment, current);
            moving = false;
            eventReading = current;
        }

        private void extendOpenTrip() {
            openTrip = openTrip == null ? DetectedTrip.openAt(assignment, current) : openTrip.extendedTo(current);
        }

        /** The vehicle stopped at this reading: the trip ends here and a stop begins. */
        private void stopAt(WindowReading stopReading) {
            events.add(MotionEvent.at(MotionEvent.Type.STOPPED, stopReading));
            if (openTrip != null) {
                closedTrips.add(openTrip.closed());
                openTrip = null;
            }
            openStop = DetectedStop.openAt(assignment, stopReading);
            moving = false;
            eventReading = stopReading;
        }

        private void closeOpenStop() {
            if (openStop != null) {
                closedStops.add(openStop.closed());
                openStop = null;
            }
        }

        /** Rule 2: the device moved to another vehicle. What was open belongs to the old vehicle and ends at its last reading. */
        private void closeEverything() {
            if (openTrip != null) {
                closedTrips.add(openTrip.closed());
                openTrip = null;
            }
            closeOpenStop();
        }

        /** Rule 1: this reading is the new starting point; nothing is known about the motion before it. */
        private void startOver() {
            assignment = reading.assignment();
            moving = false;
            eventReading = current;
            window.clear();
            openTrip = null;
            openStop = null;
        }

        MotionUpdate result() {
            window.add(current);
            pruneWindow();

            List<DetectedTrip> trips = new ArrayList<>(closedTrips);
            if (openTrip != null) {
                trips.add(openTrip);
            }
            List<DetectedStop> stops = new ArrayList<>(closedStops);
            if (openStop != null) {
                stops.add(openStop);
            }
            MotionState after = new MotionState(before.imei(), assignment, moving,
                    eventReading.recordedAt(), eventReading.latitude(), eventReading.longitude(),
                    current.recordedAt(), window, openTrip, openStop);
            return new MotionUpdate(after, true, events, trips, stops);
        }

        /**
         * Keeps the window just long enough to cover minDuration: the oldest reading kept is the newest one
         * that is at least minDuration older than the current reading (Traccar's CacheManager does the same).
         */
        private void pruneWindow() {
            int keepFrom = 0;
            for (int index = 0; index < window.size() - 1; index++) {
                long age = Duration.between(window.get(index).recordedAt(), current.recordedAt()).toMillis();
                if (age >= settings.minDurationMillis()) {
                    keepFrom = index;
                }
            }
            window.subList(0, keepFrom).clear();
        }

        private static double distance(WindowReading from, WindowReading to) {
            return GeoDistanceCalculator.distanceInMeters(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        }
    }
}
