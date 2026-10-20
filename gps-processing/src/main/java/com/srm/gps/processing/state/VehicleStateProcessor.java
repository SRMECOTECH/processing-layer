package com.srm.gps.processing.state;

import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.processing.rule.DistanceCalculator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps one vehicle's state up to date. Pure: previous state + reading in, new state + findings out.
 *
 *  - heading: bearing from the previous position (kept when the vehicle barely moved, so a parked truck keeps
 *    pointing the way it parked);
 *  - acceleration: change of reported speed over time; harsh acceleration/braking like Traccar's
 *    BehaviorEventHandler, only trusted when readings are close together (with one reading a minute the
 *    change is an average, not a "harsh" event);
 *  - Kalman-smoothed track: a constant-velocity filter on a local plane, with outlier gating; its distance is
 *    only accumulated while the vehicle is moving, so a parked truck's jitter adds nothing;
 *  - counters for data quality (late, jitter, jumps, odometer) and an exponential average of the reporting
 *    interval;
 *  - time moving vs standing (a gap longer than maxCountedGapSeconds is not credited to either).
 */
public final class VehicleStateProcessor {

    /** Settings with sensible defaults; see {@link #defaults()}. */
    public record Settings(double gpsSigmaMeters, double accelerationSigma, double harshAccelerationMps2,
                           double harshBrakingMps2, long harshMaxIntervalSeconds, long maxCountedGapSeconds,
                           long kalmanResetGapSeconds, double headingMinMoveMeters, double reoriginMeters) {

        public static Settings defaults() {
            return new Settings(12, 1.5, 2.5, -3.0, 10, 900, 600, 5, 50_000);
        }
    }

    public record Finding(Kind kind, Instant recordedAt, double latitude, double longitude, Map<String, Object> details) {
        public enum Kind { HARSH_ACCELERATION, HARSH_BRAKING }
    }

    public record Update(VehicleState state, List<Finding> findings) {
    }

    private static final double EWMA_WEIGHT = 0.2;
    private static final int KALMAN_MAX_REJECTIONS = 3;

    private VehicleStateProcessor() {
    }

    public static Update update(VehicleState previous, VehicleReading reading, Settings settings) {
        if (previous == null) {
            return new Update(first(reading, settings), List.of());
        }
        if (reading.arrivedOutOfOrder() || !reading.recordedAt().isAfter(previous.lastRecordedAt())) {
            return new Update(countLate(previous), List.of());
        }

        List<Finding> findings = new ArrayList<>();
        long dt = reading.recordedAt().getEpochSecond() - previous.lastRecordedAt().getEpochSecond();

        double moved = DistanceCalculator.distance(previous.latitude(), previous.longitude(), reading.latitude(), reading.longitude());
        // Boxed on purpose: with a primitive in one branch, a null previous heading would be unboxed and throw.
        Double heading = moved >= settings.headingMinMoveMeters()
                ? Double.valueOf(round1(DistanceCalculator.bearing(previous.latitude(), previous.longitude(), reading.latitude(), reading.longitude())))
                : previous.headingDegrees();

        Double acceleration = null;
        if (dt > 0) {
            acceleration = round2((reading.speedKmph() - previous.speedKmph()) / 3.6 / dt);
            if (dt <= settings.harshMaxIntervalSeconds()) {
                if (acceleration >= settings.harshAccelerationMps2()) {
                    findings.add(harsh(Finding.Kind.HARSH_ACCELERATION, reading, previous, acceleration, dt));
                } else if (acceleration <= settings.harshBrakingMps2()) {
                    findings.add(harsh(Finding.Kind.HARSH_BRAKING, reading, previous, acceleration, dt));
                }
            }
        }

        // Kalman filter on a local plane; re-origin (and restart) when far from the origin or after a long gap.
        double originLat = previous.originLatitude();
        double originLon = previous.originLongitude();
        double[] local = toLocal(reading.latitude(), reading.longitude(), originLat, originLon);
        KalmanFilter2D filter = previous.kalman();
        int rejected = previous.kalmanRejectedInARow();
        double kalmanDistance = previous.kalmanDistanceMeters();
        boolean restart = filter == null || dt > settings.kalmanResetGapSeconds()
                || Math.hypot(local[0], local[1]) > settings.reoriginMeters()
                || reading.distanceSource() == DistanceSource.JUMP_FILTERED;
        if (restart) {
            originLat = reading.latitude();
            originLon = reading.longitude();
            filter = KalmanFilter2D.start(0, 0, settings.gpsSigmaMeters());
            rejected = 0;
            // Bridge a silence with the distance the processing counted (believable straight line or odometer);
            // an impossible jump counted nothing and adds nothing here either.
            if (reading.moving() && reading.distanceSource() != DistanceSource.JUMP_FILTERED) {
                kalmanDistance += reading.distanceMeters();
            }
        } else {
            KalmanFilter2D predicted = filter.predict(dt, settings.accelerationSigma());
            if (predicted.mahalanobisSquared(local[0], local[1], settings.gpsSigmaMeters()) > KalmanFilter2D.OUTLIER_GATE
                    && rejected + 1 < KALMAN_MAX_REJECTIONS) {
                filter = predicted;
                rejected++;
            } else {
                KalmanFilter2D updated = rejected + 1 >= KALMAN_MAX_REJECTIONS
                        && predicted.mahalanobisSquared(local[0], local[1], settings.gpsSigmaMeters()) > KalmanFilter2D.OUTLIER_GATE
                        ? KalmanFilter2D.start(local[0], local[1], settings.gpsSigmaMeters())
                        : predicted.update(local[0], local[1], settings.gpsSigmaMeters());
                if (reading.moving()) {
                    kalmanDistance += Math.hypot(updated.east() - filter.east(), updated.north() - filter.north());
                }
                filter = updated;
                rejected = 0;
            }
        }
        double[] smoothed = toLatLon(filter.east(), filter.north(), originLat, originLon);

        long countedDt = dt <= settings.maxCountedGapSeconds() ? dt : 0;
        double interval = previous.readings() <= 1 ? dt : previous.averageIntervalSeconds() * (1 - EWMA_WEIGHT) + dt * EWMA_WEIGHT;

        VehicleState next = new VehicleState(
                reading.imei(),
                coalesce(reading.vehicleRegistrationNo(), previous.vehicleRegistrationNo()),
                coalesce(reading.tenantCode(), previous.tenantCode()),
                previous.firstSeenAt(),
                reading.recordedAt(),
                reading.latitude(),
                reading.longitude(),
                reading.speedKmph(),
                reading.moving(),
                reading.ignition() != null ? reading.ignition() : previous.ignition(),
                heading,
                acceleration,
                reading.totalDistanceMeters() != null ? reading.totalDistanceMeters() : previous.totalDistanceMeters() + reading.distanceMeters(),
                reading.odometerMeters() != null ? reading.odometerMeters() : previous.odometerMeters(),
                originLat,
                originLon,
                filter.x(),
                filter.p(),
                round6(smoothed[0]),
                round6(smoothed[1]),
                round1(filter.speedKmph()),
                round1(kalmanDistance),
                rejected,
                previous.readings() + 1,
                previous.outOfOrderReadings(),
                previous.jitterFilteredReadings() + (reading.distanceSource() == DistanceSource.JITTER_FILTERED ? 1 : 0),
                previous.jumpFilteredReadings() + (reading.distanceSource() == DistanceSource.JUMP_FILTERED ? 1 : 0),
                previous.odometerReadings() + (reading.distanceSource() == DistanceSource.DEVICE_ODOMETER ? 1 : 0),
                round1(interval),
                Math.max(previous.longestGapSeconds(), dt),
                previous.movingSeconds() + (previous.moving() ? countedDt : 0),
                previous.standingSeconds() + (previous.moving() ? 0 : countedDt),
                previous.harshAccelerations() + findings.stream().filter(f -> f.kind() == Finding.Kind.HARSH_ACCELERATION).count(),
                previous.harshBrakings() + findings.stream().filter(f -> f.kind() == Finding.Kind.HARSH_BRAKING).count());
        return new Update(next, findings);
    }

    private static VehicleState first(VehicleReading r, Settings settings) {
        KalmanFilter2D filter = KalmanFilter2D.start(0, 0, settings.gpsSigmaMeters());
        return new VehicleState(r.imei(), r.vehicleRegistrationNo(), r.tenantCode(), r.recordedAt(), r.recordedAt(),
                r.latitude(), r.longitude(), r.speedKmph(), r.moving(), r.ignition(), null, null,
                r.totalDistanceMeters() != null ? r.totalDistanceMeters() : 0, r.odometerMeters(),
                r.latitude(), r.longitude(), filter.x(), filter.p(), r.latitude(), r.longitude(), 0, 0, 0,
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static VehicleState countLate(VehicleState s) {
        return new VehicleState(s.imei(), s.vehicleRegistrationNo(), s.tenantCode(), s.firstSeenAt(), s.lastRecordedAt(),
                s.latitude(), s.longitude(), s.speedKmph(), s.moving(), s.ignition(), s.headingDegrees(), s.accelerationMps2(),
                s.totalDistanceMeters(), s.odometerMeters(), s.originLatitude(), s.originLongitude(), s.kalmanState(),
                s.kalmanCovariance(), s.kalmanLatitude(), s.kalmanLongitude(), s.kalmanSpeedKmph(), s.kalmanDistanceMeters(),
                s.kalmanRejectedInARow(), s.readings(), s.outOfOrderReadings() + 1, s.jitterFilteredReadings(),
                s.jumpFilteredReadings(), s.odometerReadings(), s.averageIntervalSeconds(), s.longestGapSeconds(),
                s.movingSeconds(), s.standingSeconds(), s.harshAccelerations(), s.harshBrakings());
    }

    private static Finding harsh(Finding.Kind kind, VehicleReading r, VehicleState previous, double acceleration, long dt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("accelerationMps2", acceleration);
        details.put("fromSpeedKmph", previous.speedKmph());
        details.put("toSpeedKmph", r.speedKmph());
        details.put("overSeconds", dt);
        return new Finding(kind, r.recordedAt(), r.latitude(), r.longitude(), details);
    }

    /** Equirectangular projection: metres east and north of the origin (accurate to well under 1% within 50 km). */
    static double[] toLocal(double lat, double lon, double originLat, double originLon) {
        double north = (lat - originLat) / DistanceCalculator.latitudeDelta(1);
        double east = (lon - originLon) / DistanceCalculator.longitudeDelta(1, originLat);
        return new double[]{east, north};
    }

    static double[] toLatLon(double east, double north, double originLat, double originLon) {
        return new double[]{originLat + DistanceCalculator.latitudeDelta(north), originLon + DistanceCalculator.longitudeDelta(east, originLat)};
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }
}
