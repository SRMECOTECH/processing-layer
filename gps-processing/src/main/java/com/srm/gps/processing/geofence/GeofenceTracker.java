package com.srm.gps.processing.geofence;

import com.srm.gps.processing.geofence.GeofenceTrackState.FenceTrack;
import com.srm.gps.processing.geofence.GeofenceTrackState.Pending;
import com.srm.gps.processing.rule.DistanceCalculator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Live geofence detection for one vehicle, one reading at a time. Pure: state + reading + index in, state + findings out.
 *
 * Per reading:
 *   1. now    = the fences the reading is inside: {@link GeofenceIndex#locate} (one hash lookup; usually none)
 *   2. check  = now + the fences the vehicle was inside or about to change sides of (the state, usually empty).
 *               A fence missing from the reading's cell is one the vehicle is truly outside of, because every fence
 *               is filed in every cell it touches - so this difference is the whole enter/exit answer.
 *   3. each fence in check moves through a small state machine (the smart_truck rules, see {@link GeofenceSettings}):
 *
 *        OUTSIDE --reading inside--> pending IN --held confirmSeconds--> INSIDE   ENTERED (stamped at the first reading inside)
 *           ^         | back outside before it held: absorbed; PASSED if it moved passMinTravelMeters, else scatter
 *           |                                                              |
 *           +---- EXITED (stamped at the last reading inside) <-- pending OUT --held, or beyond escapeFactor radii--+
 *                                  back inside before it held: absorbed (scatter at the border, a truck parked on it)
 *
 *      while INSIDE: past the dwell limit -> DWELL_EXCEEDED, once per visit.
 *   4. fences crossed between the previous reading and this one with no reading inside (a corner clipped at speed, a
 *      short data gap): the straight move is tested against the fences listed along it -> PASSED, inferred, with the
 *      time interpolated. Only when the previous reading is known and close enough in time.
 *
 * Idempotent: a reading not newer than the state's last one (a redelivery, a replayed or late reading) changes nothing.
 */
public final class GeofenceTracker {

    private GeofenceTracker() {
    }

    /**
     * @param durableChange true when the remembered fences changed: only then does the state need writing (the last
     *                      reading alone changes on every reading and is cheap to lose - see the module)
     */
    public record Update(GeofenceTrackState state, List<GeofenceFinding> findings, boolean durableChange) {
    }

    public static Update update(GeofenceTrackState state, GeofenceReading reading, GeofenceIndex index, GeofenceSettings settings) {
        if (reading.arrivedOutOfOrder()
                || (state.lastRecordedAt() != null && !reading.recordedAt().isAfter(state.lastRecordedAt()))) {
            return new Update(state, List.of(), false);
        }
        Instant previousAt = reading.secondsSincePrevious() > 0 ? reading.recordedAt().minusSeconds(reading.secondsSincePrevious()) : null;
        // the state's last reading is the device's previous one: its position can be used for the move
        boolean previousKnown = previousAt != null && state.lastRecordedAt() != null
                && Math.abs(Duration.between(state.lastRecordedAt(), previousAt).toMillis()) < 1_500;

        List<Geofence> insideNow = index.locate(reading.latitude(), reading.longitude());
        Set<Long> insideIds = new LinkedHashSet<>();
        insideNow.forEach(f -> insideIds.add(f.id()));
        Set<Long> check = new LinkedHashSet<>(state.tracks().keySet());
        check.addAll(insideIds);

        Map<Long, FenceTrack> tracks = new TreeMap<>(state.tracks());
        List<GeofenceFinding> findings = new ArrayList<>();
        for (long id : check) {
            FenceTrack before = tracks.getOrDefault(id, FenceTrack.OUTSIDE);
            Optional<Geofence> fence = index.byId(id);
            if (fence.isEmpty()) {
                // switched off or deleted while the vehicle was inside: close the visit where we are
                if (before.inside()) {
                    findings.add(GeofenceFinding.exited(placeholder(id), before.enteredAt(), reading.recordedAt(),
                            reading.latitude(), reading.longitude(), 0, before.dwellAlertAt(), "FENCE_REMOVED"));
                }
                tracks.remove(id);
                continue;
            }
            FenceTrack after = step(before, fence.get(), insideIds.contains(id), reading, previousAt,
                    previousKnown ? state : null, settings, findings);
            if (after.settledOutside()) {
                tracks.remove(id);
            } else {
                tracks.put(id, after);
            }
        }

        if (previousKnown && reading.secondsSincePrevious() <= settings.crossingMaxGapSeconds()) {
            inferCrossings(state, reading, previousAt, check, index, settings, findings);
        }

        GeofenceTrackState next = new GeofenceTrackState(reading.recordedAt(), reading.latitude(), reading.longitude(), tracks);
        return new Update(next, findings, !next.tracks().equals(state.tracks()));
    }

    private static FenceTrack step(FenceTrack track, Geofence fence, boolean inside, GeofenceReading r, Instant previousAt,
                                   GeofenceTrackState previous, GeofenceSettings s, List<GeofenceFinding> out) {
        if (inside == track.inside()) {
            FenceTrack settled = track;
            Pending p = track.pending();
            if (p != null) {
                if (!track.inside() && p.inside()) {
                    // in and out again before it held: a drive-through - or scatter at the border, if it hardly moved
                    double travelled = DistanceCalculator.distance(p.latitude(), p.longitude(), r.latitude(), r.longitude());
                    if (travelled >= s.passMinTravelMeters()) {
                        Instant lastInside = previousAt != null && !previousAt.isBefore(p.since()) ? previousAt : p.since();
                        out.add(GeofenceFinding.passed(fence, p.since(), lastInside, p.latitude(), p.longitude(), p.gapSeconds(), false));
                    }
                }
                settled = track.withPending(null);
            }
            return dwellCheck(settled, fence, r, s, out);
        }

        Pending p = track.pending();
        if (p == null) {
            p = inside
                    ? new Pending(true, r.recordedAt(), r.latitude(), r.longitude(), r.secondsSincePrevious(), null, 0, 0)
                    : new Pending(false, r.recordedAt(), r.latitude(), r.longitude(), r.secondsSincePrevious(),
                            previousAt != null ? previousAt : r.recordedAt(),
                            previous != null ? previous.lastLatitude() : r.latitude(),
                            previous != null ? previous.lastLongitude() : r.longitude());
        }
        boolean held = Duration.between(p.since(), r.recordedAt()).getSeconds() >= s.confirmSeconds();
        boolean escaped = !inside && fence.beyondEscape(r.latitude(), r.longitude(), s.escapeFactor());
        if (!held && !escaped) {
            return track.withPending(p);
        }
        if (inside) {
            out.add(GeofenceFinding.entered(fence, p.since(), p.latitude(), p.longitude(), p.gapSeconds()));
            return dwellCheck(new FenceTrack(true, p.since(), p.latitude(), p.longitude(), p.gapSeconds(), null, null), fence, r, s, out);
        }
        out.add(GeofenceFinding.exited(fence, track.enteredAt(), p.lastInsideAt(), p.lastInsideLatitude(), p.lastInsideLongitude(),
                p.gapSeconds(), track.dwellAlertAt(), held ? "HELD" : "ESCAPED"));
        return FenceTrack.OUTSIDE;
    }

    private static FenceTrack dwellCheck(FenceTrack track, Geofence fence, GeofenceReading r, GeofenceSettings s, List<GeofenceFinding> out) {
        if (!track.inside() || track.dwellAlertAt() != null || track.enteredAt() == null) {
            return track;
        }
        int limit = s.dwellAlertMinutes(fence);
        if (limit <= 0) {
            return track;
        }
        Instant alertAt = track.enteredAt().plus(Duration.ofMinutes(limit));
        if (r.recordedAt().isBefore(alertAt)) {
            return track;
        }
        out.add(GeofenceFinding.dwellExceeded(fence, track.enteredAt(), alertAt, r.latitude(), r.longitude(), limit));
        return track.withDwellAlertAt(alertAt);
    }

    private static void inferCrossings(GeofenceTrackState previous, GeofenceReading r, Instant previousAt, Set<Long> alreadyChecked,
                                       GeofenceIndex index, GeofenceSettings s, List<GeofenceFinding> out) {
        double lat1 = previous.lastLatitude();
        double lon1 = previous.lastLongitude();
        if (DistanceCalculator.distance(lat1, lon1, r.latitude(), r.longitude()) < s.crossingMinMoveMeters()) {
            return;
        }
        for (Geofence f : index.candidatesAlong(lat1, lon1, r.latitude(), r.longitude())) {
            if (alreadyChecked.contains(f.id()) || f.contains(lat1, lon1)) {
                continue;
            }
            double t = f.crossingFraction(lat1, lon1, r.latitude(), r.longitude());
            if (t < 0) {
                continue;
            }
            Instant at = previousAt.plusMillis(Math.round(t * r.secondsSincePrevious() * 1000));
            out.add(GeofenceFinding.passed(f, at, at, lat1 + t * (r.latitude() - lat1), lon1 + t * (r.longitude() - lon1),
                    r.secondsSincePrevious(), true));
        }
    }

    /** Stands in for a fence that is no longer in the index, so its open visit can still be closed by id. */
    private static Geofence placeholder(long id) {
        return Geofence.circle(id, "#" + id, "(removed fence " + id + ")", "ZONE", 0, 0, 1, 0, null);
    }
}
