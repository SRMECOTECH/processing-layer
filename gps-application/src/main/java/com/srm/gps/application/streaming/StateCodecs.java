package com.srm.gps.application.streaming;

import com.srm.gps.processing.ignition.IgnitionState;
import com.srm.gps.processing.speed.SpeedRule;
import com.srm.gps.processing.speed.SpeedViolationState;
import com.srm.gps.processing.speed.SpeedViolationState.WindowPoint;
import com.srm.gps.processing.state.VehicleState;
import com.srm.gps.processing.stoppage.StoppageState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the consumers' state records into readable Redis hashes and back.
 * Field names are plain words so the hash reads well in RedisInsight and in the vehicle tracking window.
 * A missing value is stored as an empty string.
 */
public final class StateCodecs {

    private StateCodecs() {
    }

    // ---------------------------------------------------------------- speed violation

    public static Map<String, String> speed(SpeedViolationState s, SpeedRule rule) {
        Fields f = new Fields();
        f.put("phase", s.phase());
        f.put("lastRecordedAt", s.lastRecordedAt());
        f.put("episodeStartedAt", s.episodeStartedAt());
        f.put("episodeLatitude", s.episodeLatitude());
        f.put("episodeLongitude", s.episodeLongitude());
        f.put("overLimitReadings", s.overPings());
        f.put("episodeReadings", s.readings());
        f.put("maxSpeedKmph", s.maxSpeedKmph());
        f.put("speedSumKmph", s.speedSumKmph());
        f.put("maxGpsSpeedKmph", s.maxGpsSpeedKmph());
        f.put("gpsConfirmedReadings", s.gpsAgreedPings());
        f.put("speedometerHighReadings", s.sensorHighPings());
        f.put("gpsHighReadings", s.gpsHighPings());
        f.put("mismatchStreak", s.mismatchStreak());
        f.put("mismatchReported", s.mismatchReported());
        f.put("lastOverAt", s.lastOverAt());
        f.put("lastGpsSpeedKmph", s.lastGpsSpeedKmph());
        f.put("lastVerdict", s.lastVerdict());
        f.put("window", encodeWindow(s.window()));
        f.put("ruleScope", rule.scope());
        f.put("ruleLimitKmph", rule.limitKmph());
        return f.map;
    }

    public static SpeedViolationState speed(Map<String, String> m) {
        if (m.isEmpty()) {
            return SpeedViolationState.initial();
        }
        Fields f = new Fields(m);
        return new SpeedViolationState(
                f.enumValue("phase", SpeedViolationState.Phase.class, SpeedViolationState.Phase.NORMAL),
                f.instant("lastRecordedAt"), f.instant("episodeStartedAt"),
                f.dbl("episodeLatitude"), f.dbl("episodeLongitude"),
                f.integer("overLimitReadings"), f.integer("episodeReadings"),
                f.dbl("maxSpeedKmph"), f.dbl("speedSumKmph"), f.dbl("maxGpsSpeedKmph"),
                f.integer("gpsConfirmedReadings"), f.integer("speedometerHighReadings"), f.integer("gpsHighReadings"),
                f.integer("mismatchStreak"), f.bool("mismatchReported"), f.instant("lastOverAt"),
                decodeWindow(f.string("window")), f.nullableDouble("lastGpsSpeedKmph"),
                f.enumValue("lastVerdict", SpeedViolationState.Verdict.class, SpeedViolationState.Verdict.UNVERIFIED));
    }

    private static String encodeWindow(List<WindowPoint> window) {
        List<String> parts = new ArrayList<>();
        for (WindowPoint p : window) {
            parts.add(p.recordedAt().toEpochMilli() + "," + p.latitude() + "," + p.longitude() + "," + p.speedKmph() + ","
                    + (p.odometerMeters() == null ? "" : p.odometerMeters()));
        }
        return String.join(";", parts);
    }

    private static List<WindowPoint> decodeWindow(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<WindowPoint> points = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] v = part.split(",", -1);
            points.add(new WindowPoint(Instant.ofEpochMilli(Long.parseLong(v[0])), Double.parseDouble(v[1]),
                    Double.parseDouble(v[2]), Integer.parseInt(v[3]), v[4].isEmpty() ? null : Long.parseLong(v[4])));
        }
        return List.copyOf(points);
    }

    // ---------------------------------------------------------------- ignition

    public static Map<String, String> ignition(IgnitionState s) {
        Fields f = new Fields();
        f.put("ignitionOn", s.ignitionOn());
        f.put("source", s.source());
        f.put("changedAt", s.changedAt());
        f.put("pendingValue", s.pendingValue());
        f.put("pendingReadings", s.pendingReadings());
        f.put("pendingSince", s.pendingSince());
        f.put("engineSeconds", s.engineSeconds());
        f.put("idleSince", s.idleSince());
        f.put("idleAlerted", s.idleAlerted());
        f.put("idleSecondsTotal", s.idleSecondsTotal());
        f.put("standingSince", s.standingSince());
        f.put("lastRecordedAt", s.lastRecordedAt());
        f.put("lastLatitude", s.lastLatitude());
        f.put("lastLongitude", s.lastLongitude());
        f.put("ignitionOnCount", s.ignitionOnCount());
        return f.map;
    }

    public static IgnitionState ignition(Map<String, String> m) {
        if (m.isEmpty()) {
            return IgnitionState.initial();
        }
        Fields f = new Fields(m);
        return new IgnitionState(f.nullableBool("ignitionOn"), f.string("source"), f.instant("changedAt"),
                f.nullableBool("pendingValue"), f.integer("pendingReadings"), f.instant("pendingSince"),
                f.lng("engineSeconds"), f.instant("idleSince"), f.bool("idleAlerted"), f.lng("idleSecondsTotal"),
                f.instant("standingSince"), f.instant("lastRecordedAt"), f.dbl("lastLatitude"), f.dbl("lastLongitude"),
                f.lng("ignitionOnCount"));
    }

    // ---------------------------------------------------------------- stoppage

    public static Map<String, String> stoppage(StoppageState s) {
        Fields f = new Fields();
        f.put("phase", s.phase());
        f.put("kind", s.kind());
        f.put("lastRecordedAt", s.lastRecordedAt());
        f.put("lastLatitude", s.lastLatitude());
        f.put("lastLongitude", s.lastLongitude());
        f.put("anchorAt", s.anchorAt());
        f.put("sumLatitude", s.sumLatitude());
        f.put("sumLongitude", s.sumLongitude());
        f.put("points", s.points());
        f.put("centroidLatitude", s.points() == 0 ? null : s.centroidLatitude());
        f.put("centroidLongitude", s.points() == 0 ? null : s.centroidLongitude());
        f.put("stopStartedAt", s.stopStartedAt());
        f.put("lastInsideAt", s.lastInsideAt());
        f.put("outsideInARow", s.outsideInARow());
        f.put("engineOnSeconds", s.engineOnSeconds());
        f.put("lastIgnition", s.lastIgnition());
        f.put("lastStopEndedAt", s.lastStopEndedAt());
        f.put("lastStopLatitude", s.lastStopLatitude());
        f.put("lastStopLongitude", s.lastStopLongitude());
        f.put("lastStopKind", s.lastStopKind());
        f.put("lastStopStartedAt", s.lastStopStartedAt());
        return f.map;
    }

    public static StoppageState stoppage(Map<String, String> m) {
        if (m.isEmpty()) {
            return StoppageState.initial();
        }
        Fields f = new Fields(m);
        return new StoppageState(
                f.enumValue("phase", StoppageState.Phase.class, StoppageState.Phase.MOVING),
                f.enumValue("kind", StoppageState.Kind.class, null),
                f.instant("lastRecordedAt"), f.dbl("lastLatitude"), f.dbl("lastLongitude"), f.instant("anchorAt"),
                f.dbl("sumLatitude"), f.dbl("sumLongitude"), f.integer("points"), f.instant("stopStartedAt"),
                f.instant("lastInsideAt"), f.integer("outsideInARow"), f.lng("engineOnSeconds"),
                f.nullableBool("lastIgnition"), f.instant("lastStopEndedAt"), f.dbl("lastStopLatitude"),
                f.dbl("lastStopLongitude"), f.enumValue("lastStopKind", StoppageState.Kind.class, null),
                f.instant("lastStopStartedAt"));
    }

    // ---------------------------------------------------------------- vehicle state

    public static Map<String, String> vehicle(VehicleState s) {
        Fields f = new Fields();
        f.put("imei", s.imei());
        f.put("vehicleRegistrationNo", s.vehicleRegistrationNo());
        f.put("tenantCode", s.tenantCode());
        f.put("firstSeenAt", s.firstSeenAt());
        f.put("lastRecordedAt", s.lastRecordedAt());
        f.put("latitude", s.latitude());
        f.put("longitude", s.longitude());
        f.put("speedKmph", s.speedKmph());
        f.put("moving", s.moving());
        f.put("ignition", s.ignition());
        f.put("headingDegrees", s.headingDegrees());
        f.put("accelerationMps2", s.accelerationMps2());
        f.put("totalDistanceMeters", s.totalDistanceMeters());
        f.put("odometerMeters", s.odometerMeters());
        f.put("originLatitude", s.originLatitude());
        f.put("originLongitude", s.originLongitude());
        f.put("kalmanState", join(s.kalmanState()));
        f.put("kalmanCovariance", join(s.kalmanCovariance()));
        f.put("kalmanLatitude", s.kalmanLatitude());
        f.put("kalmanLongitude", s.kalmanLongitude());
        f.put("kalmanSpeedKmph", s.kalmanSpeedKmph());
        f.put("kalmanDistanceMeters", s.kalmanDistanceMeters());
        f.put("kalmanRejectedInARow", s.kalmanRejectedInARow());
        f.put("readings", s.readings());
        f.put("outOfOrderReadings", s.outOfOrderReadings());
        f.put("jitterFilteredReadings", s.jitterFilteredReadings());
        f.put("jumpFilteredReadings", s.jumpFilteredReadings());
        f.put("odometerReadings", s.odometerReadings());
        f.put("averageIntervalSeconds", s.averageIntervalSeconds());
        f.put("longestGapSeconds", s.longestGapSeconds());
        f.put("movingSeconds", s.movingSeconds());
        f.put("standingSeconds", s.standingSeconds());
        f.put("harshAccelerations", s.harshAccelerations());
        f.put("harshBrakings", s.harshBrakings());
        return f.map;
    }

    public static VehicleState vehicle(Map<String, String> m) {
        if (m.isEmpty() || m.get("lastRecordedAt") == null || m.get("lastRecordedAt").isEmpty()) {
            return null;
        }
        Fields f = new Fields(m);
        return new VehicleState(f.string("imei"), f.string("vehicleRegistrationNo"), f.string("tenantCode"),
                f.instant("firstSeenAt"), f.instant("lastRecordedAt"), f.dbl("latitude"), f.dbl("longitude"),
                f.integer("speedKmph"), f.bool("moving"), f.nullableBool("ignition"), f.nullableDouble("headingDegrees"),
                f.nullableDouble("accelerationMps2"), f.dbl("totalDistanceMeters"), f.nullableLong("odometerMeters"),
                f.dbl("originLatitude"), f.dbl("originLongitude"), split(f.string("kalmanState")),
                split(f.string("kalmanCovariance")), f.dbl("kalmanLatitude"), f.dbl("kalmanLongitude"),
                f.dbl("kalmanSpeedKmph"), f.dbl("kalmanDistanceMeters"), f.integer("kalmanRejectedInARow"),
                f.lng("readings"), f.lng("outOfOrderReadings"), f.lng("jitterFilteredReadings"),
                f.lng("jumpFilteredReadings"), f.lng("odometerReadings"), f.dbl("averageIntervalSeconds"),
                f.lng("longestGapSeconds"), f.lng("movingSeconds"), f.lng("standingSeconds"),
                f.lng("harshAccelerations"), f.lng("harshBrakings"));
    }

    // ---------------------------------------------------------------- speed rule

    public static Map<String, String> rule(SpeedRule r) {
        Fields f = new Fields();
        f.put("scope", r.scope());
        f.put("limitKmph", r.limitKmph());
        f.put("minDurationSeconds", r.minDurationSeconds());
        f.put("minConsecutivePings", r.minConsecutivePings());
        f.put("hysteresisKmph", r.hysteresisKmph());
        f.put("toleranceKmph", r.toleranceKmph());
        f.put("tolerancePercent", r.tolerancePercent());
        f.put("maxGapSeconds", r.maxGapSeconds());
        f.put("windowPings", r.windowPings());
        f.put("requireGpsAgreement", r.requireGpsAgreement());
        f.put("mismatchPings", r.mismatchPings());
        return f.map;
    }

    public static SpeedRule rule(String scope, Map<String, String> m) {
        SpeedRule d = SpeedRule.defaults();
        Fields f = new Fields(m);
        return new SpeedRule(scope,
                f.dblOr("limitKmph", d.limitKmph()),
                f.lngOr("minDurationSeconds", d.minDurationSeconds()),
                (int) f.lngOr("minConsecutivePings", d.minConsecutivePings()),
                f.dblOr("hysteresisKmph", d.hysteresisKmph()),
                f.dblOr("toleranceKmph", d.toleranceKmph()),
                f.dblOr("tolerancePercent", d.tolerancePercent()),
                f.lngOr("maxGapSeconds", d.maxGapSeconds()),
                (int) f.lngOr("windowPings", d.windowPings()),
                m.containsKey("requireGpsAgreement") ? f.bool("requireGpsAgreement") : d.requireGpsAgreement(),
                (int) f.lngOr("mismatchPings", d.mismatchPings()));
    }

    // ---------------------------------------------------------------- helpers

    private static String join(double[] values) {
        if (values == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(values[i]);
        }
        return text.toString();
    }

    private static double[] split(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Arrays.stream(text.split(",")).mapToDouble(Double::parseDouble).toArray();
    }

    /** A small typed view over a Redis hash. */
    static final class Fields {
        final Map<String, String> map;

        Fields() {
            this.map = new LinkedHashMap<>();
        }

        Fields(Map<String, String> map) {
            this.map = map;
        }

        void put(String name, Object value) {
            map.put(name, value == null ? "" : value instanceof Enum<?> e ? e.name() : value.toString());
        }

        String string(String name) {
            String value = map.get(name);
            return value == null || value.isEmpty() ? null : value;
        }

        Instant instant(String name) {
            String value = string(name);
            return value == null ? null : Instant.parse(value);
        }

        double dbl(String name) {
            String value = string(name);
            return value == null ? 0 : Double.parseDouble(value);
        }

        double dblOr(String name, double fallback) {
            String value = string(name);
            return value == null ? fallback : Double.parseDouble(value);
        }

        Double nullableDouble(String name) {
            String value = string(name);
            return value == null ? null : Double.parseDouble(value);
        }

        int integer(String name) {
            String value = string(name);
            return value == null ? 0 : (int) Double.parseDouble(value);
        }

        long lng(String name) {
            String value = string(name);
            return value == null ? 0 : (long) Double.parseDouble(value);
        }

        long lngOr(String name, long fallback) {
            String value = string(name);
            return value == null ? fallback : (long) Double.parseDouble(value);
        }

        Long nullableLong(String name) {
            String value = string(name);
            return value == null ? null : (long) Double.parseDouble(value);
        }

        boolean bool(String name) {
            return Boolean.parseBoolean(string(name));
        }

        Boolean nullableBool(String name) {
            String value = string(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        <E extends Enum<E>> E enumValue(String name, Class<E> type, E fallback) {
            String value = string(name);
            return value == null ? fallback : Enum.valueOf(type, value);
        }
    }
}
