package com.srm.gps.processing.speed;

import java.time.Instant;
import java.util.List;

/**
 * Everything the speed check remembers about one device between readings (kept in Redis gps:state:speed:{imei}).
 *
 * @param phase             NORMAL, CANDIDATE (over the limit, not confirmed yet) or VIOLATING (confirmed episode)
 * @param lastRecordedAt    newest reading applied; older ones are skipped (Traccar evaluates events in time order only)
 * @param episodeStartedAt  first over-limit reading of the current episode
 * @param overPings         over-limit readings in the current episode
 * @param readings          readings in the current episode (over-limit and in the hysteresis band)
 * @param maxSpeedKmph      highest reported speed in the episode
 * @param speedSumKmph      for the average
 * @param maxGpsSpeedKmph   highest GPS-derived speed in the episode
 * @param gpsAgreedPings    over-limit readings the GPS confirmed
 * @param sensorHighPings   over-limit readings the GPS contradicted (speedometer high, GPS low)
 * @param gpsHighPings      readings under the limit by speedometer but over it by GPS
 * @param mismatchStreak    consecutive readings where speedometer and GPS disagree
 * @param mismatchReported  SPEED_SENSOR_MISMATCH already raised for the current streak
 * @param window            the last few readings, for the GPS speed (oldest first)
 * @param lastGpsSpeedKmph  GPS speed at the newest reading, or null
 * @param lastVerdict       verification of the newest reading
 */
public record SpeedViolationState(
        Phase phase,
        Instant lastRecordedAt,
        Instant episodeStartedAt,
        double episodeLatitude,
        double episodeLongitude,
        int overPings,
        int readings,
        double maxSpeedKmph,
        double speedSumKmph,
        double maxGpsSpeedKmph,
        int gpsAgreedPings,
        int sensorHighPings,
        int gpsHighPings,
        int mismatchStreak,
        boolean mismatchReported,
        Instant lastOverAt,
        List<WindowPoint> window,
        Double lastGpsSpeedKmph,
        Verdict lastVerdict) {

    public enum Phase { NORMAL, CANDIDATE, VIOLATING }

    /** How one reading's speedometer value compares with the GPS. */
    public enum Verdict {
        /** Under the limit and the GPS agrees. */
        OK,
        /** Over the limit and the GPS confirms it. */
        GPS_CONFIRMED,
        /** Over the limit by speedometer but the GPS says much slower: faulty or tampered speedometer. */
        SENSOR_HIGH,
        /** Under the limit by speedometer but the GPS says over: under-reporting speedometer or GPS glitch. */
        GPS_HIGH,
        /** Not enough readings (or time) to work out a GPS speed. */
        UNVERIFIED
    }

    /** One reading kept for the GPS speed window. */
    public record WindowPoint(Instant recordedAt, double latitude, double longitude, int speedKmph, Long odometerMeters) {
    }

    public static SpeedViolationState initial() {
        return new SpeedViolationState(Phase.NORMAL, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null,
                List.of(), null, Verdict.UNVERIFIED);
    }

    public double averageSpeedKmph() {
        return readings == 0 ? 0 : speedSumKmph / readings;
    }
}
