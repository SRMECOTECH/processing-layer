package com.srm.gps.processing.speed;

import com.srm.gps.processing.rule.DistanceCalculator;
import com.srm.gps.processing.speed.SpeedViolationState.Phase;
import com.srm.gps.processing.speed.SpeedViolationState.Verdict;
import com.srm.gps.processing.speed.SpeedViolationState.WindowPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sustained over-speed detection with speedometer/GPS cross-verification. Pure: state in, state + findings out.
 *
 *   NORMAL ──over──▶ CANDIDATE ──(≥ minConsecutivePings over AND ≥ minDurationSeconds)──▶ VIOLATING  (raise once)
 *      ▲                 │ speed < limit - hysteresis (a short burst: dropped, nothing raised)   │
 *      └─────────────────┴──────────────────── speed < limit - hysteresis, or a data gap ◀───────┘ (episode closed)
 *
 * Speeds between (limit - hysteresis) and limit keep an episode alive without counting as over (hysteresis band),
 * the way Teltonika keeps an overspeed alert until speed falls clearly below the limit.
 *
 * Every reading is cross-checked: the GPS speed is the path length over the last {@code windowPings} readings
 * divided by the time they span (the device odometer is used for the length when it reports one). A single
 * leg would be dominated by GPS noise; a short window smooths it while staying close to "now".
 * The episode's verdict says whether the GPS backed the speedometer:
 *   GPS_VERIFIED   - most over-limit readings were confirmed by the GPS
 *   SENSOR_SUSPECT - the GPS mostly contradicted the speedometer (faulty or tampered sensor)
 *   UNVERIFIED     - too few readings to tell
 */
public final class SpeedViolationProcessor {

    private static final double MIN_WINDOW_SECONDS = 5;
    /** Below this both sensors are just crawling; their disagreement says nothing. */
    private static final double MISMATCH_MIN_SPEED_KMPH = 20;
    /** A leg faster than this is a GPS glitch or relocation, not driving (as in the distance step). */
    private static final double MAX_PLAUSIBLE_KMPH = 200;

    private SpeedViolationProcessor() {
    }

    public record Update(SpeedViolationState state, List<SpeedFinding> findings) {
    }

    public static Update update(SpeedViolationState state, SpeedReading reading, SpeedRule rule) {
        if (reading.arrivedOutOfOrder() || (state.lastRecordedAt() != null && !reading.recordedAt().isAfter(state.lastRecordedAt()))) {
            return new Update(state, List.of());
        }
        List<SpeedFinding> findings = new ArrayList<>();
        SpeedViolationState current = state;

        // A long silence breaks continuity: close what was open, then judge this reading on its own.
        if (current.lastRecordedAt() != null && current.phase() != Phase.NORMAL
                && Duration.between(current.lastRecordedAt(), reading.recordedAt()).getSeconds() > rule.maxGapSeconds()) {
            if (current.phase() == Phase.VIOLATING) {
                findings.add(ended(current, rule, "DATA_GAP"));
            }
            current = reset(current);
        }

        List<WindowPoint> window = slide(current.window(), reading, Math.max(2, rule.windowPings()));
        Double gpsSpeed = gpsSpeed(window, rule.maxGapSeconds());
        int speed = reading.speedKmph();
        Verdict verdict = verdict(speed, gpsSpeed, rule);
        boolean disagree = gpsSpeed != null && Math.max(speed, gpsSpeed) >= MISMATCH_MIN_SPEED_KMPH
                && Math.abs(speed - gpsSpeed) > rule.tolerance(Math.max(speed, gpsSpeed));

        int mismatchStreak = disagree ? current.mismatchStreak() + 1 : 0;
        boolean mismatchReported = disagree && current.mismatchReported();
        if (disagree && !mismatchReported && mismatchStreak >= rule.mismatchPings()) {
            findings.add(mismatch(reading, gpsSpeed, mismatchStreak, rule));
            mismatchReported = true;
        }

        boolean over = speed > rule.limitKmph() && !(rule.requireGpsAgreement() && verdict == Verdict.SENSOR_HIGH);
        boolean belowBand = speed < rule.limitKmph() - rule.hysteresisKmph();

        SpeedViolationState next = switch (current.phase()) {
            case NORMAL -> over ? startEpisode(current, reading, verdict, gpsSpeed) : current;
            case CANDIDATE -> {
                if (over) {
                    SpeedViolationState counted = count(current, reading, verdict, gpsSpeed, true);
                    if (counted.overPings() >= rule.minConsecutivePings()
                            && Duration.between(counted.episodeStartedAt(), reading.recordedAt()).getSeconds() >= rule.minDurationSeconds()) {
                        SpeedViolationState violating = withPhase(counted, Phase.VIOLATING);
                        findings.add(confirmed(violating, rule, reading.recordedAt()));
                        yield violating;
                    }
                    yield counted;
                }
                yield belowBand ? reset(current) : count(current, reading, verdict, gpsSpeed, false);
            }
            case VIOLATING -> {
                if (belowBand) {
                    findings.add(ended(current, rule, "SPEED_DROPPED"));
                    yield reset(current);
                }
                yield count(current, reading, verdict, gpsSpeed, over);
            }
        };

        return new Update(new SpeedViolationState(next.phase(), reading.recordedAt(), next.episodeStartedAt(),
                next.episodeLatitude(), next.episodeLongitude(), next.overPings(), next.readings(), next.maxSpeedKmph(),
                next.speedSumKmph(), next.maxGpsSpeedKmph(), next.gpsAgreedPings(), next.sensorHighPings(),
                next.gpsHighPings() + (verdict == Verdict.GPS_HIGH ? 1 : 0), mismatchStreak, mismatchReported,
                next.lastOverAt(), window, gpsSpeed, verdict), findings);
    }

    /**
     * Path length over the window divided by the time it spans, in km/h. Null ("cannot verify") when the window
     * is too short, or when one of its legs is physically impossible (a GPS glitch or a relocation: the same
     * plausibility limit as the distance step's max-speed filter). A long silence cuts the window: an average over
     * a gap says nothing about the speed now.
     */
    static Double gpsSpeed(List<WindowPoint> window, long maxGapSeconds) {
        int start = 0;
        for (int i = 1; i < window.size(); i++) {
            if (Duration.between(window.get(i - 1).recordedAt(), window.get(i).recordedAt()).getSeconds() > maxGapSeconds) {
                start = i;
            }
        }
        List<WindowPoint> w = window.subList(start, window.size());
        if (w.size() < 2) {
            return null;
        }
        WindowPoint first = w.getFirst();
        WindowPoint last = w.getLast();
        double seconds = Duration.between(first.recordedAt(), last.recordedAt()).toMillis() / 1000.0;
        if (seconds < MIN_WINDOW_SECONDS) {
            return null;
        }
        double path = 0;
        for (int i = 1; i < w.size(); i++) {
            WindowPoint a = w.get(i - 1);
            WindowPoint b = w.get(i);
            double leg = DistanceCalculator.distance(a.latitude(), a.longitude(), b.latitude(), b.longitude());
            double legSeconds = Duration.between(a.recordedAt(), b.recordedAt()).toMillis() / 1000.0;
            if (legSeconds > 0 && leg / legSeconds * 3.6 > MAX_PLAUSIBLE_KMPH) {
                return null;
            }
            path += leg;
        }
        double meters = first.odometerMeters() != null && last.odometerMeters() != null && last.odometerMeters() >= first.odometerMeters()
                ? last.odometerMeters() - first.odometerMeters()
                : path;
        return meters / seconds * 3.6;
    }

    static Verdict verdict(int speed, Double gpsSpeed, SpeedRule rule) {
        if (gpsSpeed == null) {
            return Verdict.UNVERIFIED;
        }
        double tolerance = rule.tolerance(rule.limitKmph());
        if (speed > rule.limitKmph()) {
            return gpsSpeed >= rule.limitKmph() - tolerance ? Verdict.GPS_CONFIRMED : Verdict.SENSOR_HIGH;
        }
        return gpsSpeed > rule.limitKmph() + tolerance ? Verdict.GPS_HIGH : Verdict.OK;
    }

    private static List<WindowPoint> slide(List<WindowPoint> window, SpeedReading reading, int size) {
        List<WindowPoint> next = new ArrayList<>(window);
        next.add(new WindowPoint(reading.recordedAt(), reading.latitude(), reading.longitude(), reading.speedKmph(), reading.odometerMeters()));
        while (next.size() > size) {
            next.removeFirst();
        }
        return List.copyOf(next);
    }

    private static SpeedViolationState startEpisode(SpeedViolationState s, SpeedReading r, Verdict verdict, Double gpsSpeed) {
        SpeedViolationState started = new SpeedViolationState(Phase.CANDIDATE, s.lastRecordedAt(), r.recordedAt(),
                r.latitude(), r.longitude(), 0, 0, 0, 0, 0, 0, 0, s.gpsHighPings(), s.mismatchStreak(),
                s.mismatchReported(), null, s.window(), s.lastGpsSpeedKmph(), s.lastVerdict());
        return count(started, r, verdict, gpsSpeed, true);
    }

    private static SpeedViolationState count(SpeedViolationState s, SpeedReading r, Verdict verdict, Double gpsSpeed, boolean over) {
        return new SpeedViolationState(s.phase(), s.lastRecordedAt(), s.episodeStartedAt(), s.episodeLatitude(),
                s.episodeLongitude(),
                s.overPings() + (over ? 1 : 0),
                s.readings() + 1,
                Math.max(s.maxSpeedKmph(), r.speedKmph()),
                s.speedSumKmph() + r.speedKmph(),
                gpsSpeed == null ? s.maxGpsSpeedKmph() : Math.max(s.maxGpsSpeedKmph(), gpsSpeed),
                s.gpsAgreedPings() + (over && verdict == Verdict.GPS_CONFIRMED ? 1 : 0),
                s.sensorHighPings() + (over && verdict == Verdict.SENSOR_HIGH ? 1 : 0),
                s.gpsHighPings(), s.mismatchStreak(), s.mismatchReported(),
                over ? r.recordedAt() : s.lastOverAt(),
                s.window(), s.lastGpsSpeedKmph(), s.lastVerdict());
    }

    private static SpeedViolationState withPhase(SpeedViolationState s, Phase phase) {
        return new SpeedViolationState(phase, s.lastRecordedAt(), s.episodeStartedAt(), s.episodeLatitude(),
                s.episodeLongitude(), s.overPings(), s.readings(), s.maxSpeedKmph(), s.speedSumKmph(), s.maxGpsSpeedKmph(),
                s.gpsAgreedPings(), s.sensorHighPings(), s.gpsHighPings(), s.mismatchStreak(), s.mismatchReported(),
                s.lastOverAt(), s.window(), s.lastGpsSpeedKmph(), s.lastVerdict());
    }

    private static SpeedViolationState reset(SpeedViolationState s) {
        return new SpeedViolationState(Phase.NORMAL, s.lastRecordedAt(), null, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                s.gpsHighPings(), s.mismatchStreak(), s.mismatchReported(), null, s.window(), s.lastGpsSpeedKmph(), s.lastVerdict());
    }

    /** GPS_VERIFIED, SENSOR_SUSPECT or UNVERIFIED for the whole episode. */
    static String episodeVerdict(SpeedViolationState s) {
        int checked = s.gpsAgreedPings() + s.sensorHighPings();
        if (checked == 0) {
            return "UNVERIFIED";
        }
        return s.sensorHighPings() > s.gpsAgreedPings() ? "SENSOR_SUSPECT" : "GPS_VERIFIED";
    }

    private static SpeedFinding confirmed(SpeedViolationState s, SpeedRule rule, Instant now) {
        return new SpeedFinding(SpeedFinding.Kind.VIOLATION_CONFIRMED, s.episodeStartedAt(), null,
                s.episodeLatitude(), s.episodeLongitude(), s.maxSpeedKmph() - rule.limitKmph(), episodeVerdict(s),
                details(s, rule, Duration.between(s.episodeStartedAt(), now).getSeconds(), null));
    }

    private static SpeedFinding ended(SpeedViolationState s, SpeedRule rule, String reason) {
        Instant end = s.lastOverAt() != null ? s.lastOverAt() : s.lastRecordedAt();
        return new SpeedFinding(SpeedFinding.Kind.VIOLATION_ENDED, s.episodeStartedAt(), end,
                s.episodeLatitude(), s.episodeLongitude(), s.maxSpeedKmph() - rule.limitKmph(), episodeVerdict(s),
                details(s, rule, Duration.between(s.episodeStartedAt(), end).getSeconds(), reason));
    }

    private static SpeedFinding mismatch(SpeedReading r, double gpsSpeed, int streak, SpeedRule rule) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("speedometerKmph", r.speedKmph());
        details.put("gpsSpeedKmph", round1(gpsSpeed));
        details.put("differenceKmph", round1(r.speedKmph() - gpsSpeed));
        details.put("readingsInARow", streak);
        details.put("toleranceKmph", round1(rule.tolerance(Math.max(r.speedKmph(), gpsSpeed))));
        details.put("ruleScope", rule.scope());
        return new SpeedFinding(SpeedFinding.Kind.SENSOR_MISMATCH, r.recordedAt(), r.recordedAt(), r.latitude(),
                r.longitude(), 0, r.speedKmph() > gpsSpeed ? "SPEEDOMETER_HIGH" : "SPEEDOMETER_LOW", details);
    }

    private static Map<String, Object> details(SpeedViolationState s, SpeedRule rule, long durationSeconds, String endReason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limitKmph", rule.limitKmph());
        details.put("ruleScope", rule.scope());
        details.put("durationSeconds", durationSeconds);
        details.put("overLimitReadings", s.overPings());
        details.put("readings", s.readings());
        details.put("maxSpeedKmph", round1(s.maxSpeedKmph()));
        details.put("averageSpeedKmph", round1(s.averageSpeedKmph()));
        details.put("maxGpsSpeedKmph", round1(s.maxGpsSpeedKmph()));
        details.put("gpsConfirmedReadings", s.gpsAgreedPings());
        details.put("speedometerHighReadings", s.sensorHighPings());
        details.put("verdict", episodeVerdict(s));
        if (endReason != null) {
            details.put("endReason", endReason);
        }
        return details;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
