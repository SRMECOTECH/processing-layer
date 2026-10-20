package com.srm.gps.processing.stoppage;

import com.srm.gps.processing.rule.DistanceCalculator;
import com.srm.gps.processing.stoppage.StoppageState.Kind;
import com.srm.gps.processing.stoppage.StoppageState.Phase;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-time stoppage detection: a streaming stay-point detector (Li/Zheng stay points, scanned chronologically
 * the way Lachesis does) with ignition awareness. Pure: state + reading in, new state + findings out.
 *
 *   MOVING ──standing──▶ CANDIDATE ──(still within radius for minDuration)──▶ STOPPED ──(exitConfirm readings
 *                            │ left the radius                                   │            outside the radius)──▶ MOVING
 *                            ▼                                                   ▼
 *                         MOVING                                    STOPPAGE ⇄ PARKED on ignition change
 *
 * "Standing" is judged on both the speedometer and the real speed worked out from distance over time since the
 * previous reading, so a frozen speedometer cannot hide a moving truck and a speedometer that never reads zero
 * cannot hide a parked one.
 *
 * Ignition decides what the stop means:
 *   engine ON  -> STOPPAGE: an alarm, to be checked against traffic and weather by the context agents
 *   engine OFF -> PARKED:   no alarm ("neutralised"): stopping with the engine off is what parking is
 *   unknown    -> STOPPAGE, flagged as such (better an explained alarm than a missed one)
 * The kind flips if the ignition changes during the stop (closing one event and opening the other).
 *
 * The stop is back-dated to the first reading at the place, and its position is the centroid of all readings in it.
 */
public final class StoppageDetector {

    /** Legs slower than this (km/h) count as standing even with GPS noise on short intervals. */
    private static final double LEG_NOISE_KMPH = 6;

    private StoppageDetector() {
    }

    public record Update(StoppageState state, List<StopFinding> findings) {
    }

    public static Update update(StoppageState state, StoppageReading r, StoppageSettings cfg) {
        if (r.arrivedOutOfOrder() || (state.lastRecordedAt() != null && !r.recordedAt().isAfter(state.lastRecordedAt()))) {
            return new Update(state, List.of());
        }
        List<StopFinding> findings = new ArrayList<>();
        long dt = state.lastRecordedAt() == null ? 0 : Duration.between(state.lastRecordedAt(), r.recordedAt()).getSeconds();
        Double legSpeed = state.lastRecordedAt() == null || dt <= 0 ? null
                : DistanceCalculator.distance(state.lastLatitude(), state.lastLongitude(), r.latitude(), r.longitude()) / dt * 3.6;
        boolean standing = r.speedKmph() <= cfg.stationarySpeedKmph()
                && (legSpeed == null || legSpeed <= Math.max(cfg.stationarySpeedKmph() * 2, LEG_NOISE_KMPH));

        StoppageState s = state;

        // A long silence: a candidate cannot be trusted; a stop survives only if the vehicle is still there.
        if (s.lastRecordedAt() != null && dt > cfg.maxGapSeconds()) {
            if (s.phase() == Phase.CANDIDATE) {
                s = moving(s);
            } else if (s.phase() == Phase.STOPPED && distanceToCentroid(s, r) > cfg.radiusMeters()) {
                findings.add(ended(s));
                s = rememberAndMove(s);
            }
        }

        switch (s.phase()) {
            case MOVING -> {
                if (standing) {
                    s = candidate(s, r);
                }
            }
            case CANDIDATE -> {
                if (distanceToCentroid(s, r) <= cfg.radiusMeters()) {
                    s = addPoint(s, r);
                    if (Duration.between(s.anchorAt(), r.recordedAt()).getSeconds() >= cfg.minDurationSeconds()) {
                        s = confirm(s, r, cfg, findings);
                    }
                } else {
                    s = moving(s);
                    if (standing) {
                        s = candidate(s, r);
                    }
                }
            }
            case STOPPED -> {
                if (distanceToCentroid(s, r) <= cfg.radiusMeters()) {
                    long engineDt = Boolean.TRUE.equals(s.lastIgnition()) && dt <= cfg.maxGapSeconds() ? dt : 0;
                    s = inside(addPoint(s, r), r, engineDt);
                    s = flipKindOnIgnition(s, r, findings);
                } else {
                    s = outside(s);
                    if (s.outsideInARow() >= cfg.exitConfirmReadings()) {
                        findings.add(ended(s));
                        s = rememberAndMove(s);
                        if (standing) {
                            s = candidate(s, r);
                        }
                    }
                }
            }
        }

        return new Update(new StoppageState(s.phase(), s.kind(), r.recordedAt(), r.latitude(), r.longitude(), s.anchorAt(),
                s.sumLatitude(), s.sumLongitude(), s.points(), s.stopStartedAt(), s.lastInsideAt(), s.outsideInARow(),
                s.engineOnSeconds(), r.ignition() != null ? r.ignition() : s.lastIgnition(), s.lastStopEndedAt(),
                s.lastStopLatitude(), s.lastStopLongitude(), s.lastStopKind(), s.lastStopStartedAt()), findings);
    }

    private static StoppageState confirm(StoppageState s, StoppageReading r, StoppageSettings cfg, List<StopFinding> findings) {
        Kind kind = Boolean.FALSE.equals(r.ignition()) ? Kind.PARKED : Kind.STOPPAGE;
        boolean merged = s.lastStopEndedAt() != null && s.lastStopKind() == kind
                && Duration.between(s.lastStopEndedAt(), s.anchorAt()).getSeconds() <= cfg.mergeGapSeconds()
                && DistanceCalculator.distance(s.lastStopLatitude(), s.lastStopLongitude(), s.centroidLatitude(), s.centroidLongitude()) <= cfg.radiusMeters();
        Instant startedAt = merged ? s.lastStopStartedAt() : s.anchorAt();
        StoppageState stopped = new StoppageState(Phase.STOPPED, kind, s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(),
                s.anchorAt(), s.sumLatitude(), s.sumLongitude(), s.points(), startedAt, r.recordedAt(), 0,
                0, s.lastIgnition(), s.lastStopEndedAt(), s.lastStopLatitude(), s.lastStopLongitude(), s.lastStopKind(),
                s.lastStopStartedAt());
        findings.add(new StopFinding(kind, true, startedAt, null, stopped.centroidLatitude(), stopped.centroidLongitude(),
                Duration.between(startedAt, r.recordedAt()).getSeconds(), 0, stopped.points(), r.ignition(), r.ignitionSource(), merged));
        return stopped;
    }

    /** Engine switched off during a STOPPAGE -> it becomes PARKED (neutralised); switched on while PARKED -> STOPPAGE. */
    private static StoppageState flipKindOnIgnition(StoppageState s, StoppageReading r, List<StopFinding> findings) {
        Kind wanted = r.ignition() == null ? s.kind() : (r.ignition() ? Kind.STOPPAGE : Kind.PARKED);
        if (wanted == s.kind()) {
            return s;
        }
        findings.add(new StopFinding(s.kind(), false, s.stopStartedAt(), r.recordedAt(), s.centroidLatitude(), s.centroidLongitude(),
                Duration.between(s.stopStartedAt(), r.recordedAt()).getSeconds(), s.engineOnSeconds(), s.points(),
                r.ignition(), r.ignitionSource(), false));
        findings.add(new StopFinding(wanted, true, r.recordedAt(), null, s.centroidLatitude(), s.centroidLongitude(), 0, 0,
                s.points(), r.ignition(), r.ignitionSource(), false));
        return new StoppageState(s.phase(), wanted, s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), s.anchorAt(),
                s.sumLatitude(), s.sumLongitude(), s.points(), r.recordedAt(), s.lastInsideAt(), s.outsideInARow(), 0,
                s.lastIgnition(), s.lastStopEndedAt(), s.lastStopLatitude(), s.lastStopLongitude(), s.lastStopKind(), s.lastStopStartedAt());
    }

    private static StopFinding ended(StoppageState s) {
        Instant end = s.lastInsideAt() != null ? s.lastInsideAt() : s.lastRecordedAt();
        return new StopFinding(s.kind(), false, s.stopStartedAt(), end, s.centroidLatitude(), s.centroidLongitude(),
                Duration.between(s.stopStartedAt(), end).getSeconds(), s.engineOnSeconds(), s.points(), s.lastIgnition(), null, false);
    }

    private static double distanceToCentroid(StoppageState s, StoppageReading r) {
        return DistanceCalculator.distance(s.centroidLatitude(), s.centroidLongitude(), r.latitude(), r.longitude());
    }

    private static StoppageState candidate(StoppageState s, StoppageReading r) {
        return new StoppageState(Phase.CANDIDATE, null, s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), r.recordedAt(),
                r.latitude(), r.longitude(), 1, null, r.recordedAt(), 0, 0, s.lastIgnition(), s.lastStopEndedAt(),
                s.lastStopLatitude(), s.lastStopLongitude(), s.lastStopKind(), s.lastStopStartedAt());
    }

    private static StoppageState moving(StoppageState s) {
        return new StoppageState(Phase.MOVING, null, s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), null, 0, 0, 0,
                null, null, 0, 0, s.lastIgnition(), s.lastStopEndedAt(), s.lastStopLatitude(), s.lastStopLongitude(),
                s.lastStopKind(), s.lastStopStartedAt());
    }

    private static StoppageState rememberAndMove(StoppageState s) {
        Instant end = s.lastInsideAt() != null ? s.lastInsideAt() : s.lastRecordedAt();
        return new StoppageState(Phase.MOVING, null, s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), null, 0, 0, 0,
                null, null, 0, 0, s.lastIgnition(), end, s.centroidLatitude(), s.centroidLongitude(), s.kind(), s.stopStartedAt());
    }

    private static StoppageState addPoint(StoppageState s, StoppageReading r) {
        return new StoppageState(s.phase(), s.kind(), s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), s.anchorAt(),
                s.sumLatitude() + r.latitude(), s.sumLongitude() + r.longitude(), s.points() + 1, s.stopStartedAt(),
                s.lastInsideAt(), s.outsideInARow(), s.engineOnSeconds(), s.lastIgnition(), s.lastStopEndedAt(),
                s.lastStopLatitude(), s.lastStopLongitude(), s.lastStopKind(), s.lastStopStartedAt());
    }

    private static StoppageState inside(StoppageState s, StoppageReading r, long engineDt) {
        return new StoppageState(s.phase(), s.kind(), s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), s.anchorAt(),
                s.sumLatitude(), s.sumLongitude(), s.points(), s.stopStartedAt(), r.recordedAt(), 0,
                s.engineOnSeconds() + engineDt, s.lastIgnition(), s.lastStopEndedAt(), s.lastStopLatitude(),
                s.lastStopLongitude(), s.lastStopKind(), s.lastStopStartedAt());
    }

    private static StoppageState outside(StoppageState s) {
        return new StoppageState(s.phase(), s.kind(), s.lastRecordedAt(), s.lastLatitude(), s.lastLongitude(), s.anchorAt(),
                s.sumLatitude(), s.sumLongitude(), s.points(), s.stopStartedAt(), s.lastInsideAt(), s.outsideInARow() + 1,
                s.engineOnSeconds(), s.lastIgnition(), s.lastStopEndedAt(), s.lastStopLatitude(), s.lastStopLongitude(),
                s.lastStopKind(), s.lastStopStartedAt());
    }
}
