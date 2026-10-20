package com.srm.gps.processing.speed;

/**
 * A speed rule for one entity (a vehicle, a company, or the default). Kept in Redis so it can be changed live.
 *
 * Based on Traccar's overspeed rule (limit x multiplier, minimal duration, fire once per episode) plus what
 * fleet telematics vendors add on top: a minimum run of consecutive over-limit pings (a single spike is not
 * speeding), hysteresis before the episode ends, and cross-verification of the speedometer against the speed
 * worked out from positions over the last few pings.
 *
 * @param scope                where the rule came from: default, tenant:{code} or vehicle:{registration}
 * @param limitKmph            the speed limit
 * @param minDurationSeconds   over the limit at least this long ...
 * @param minConsecutivePings  ... and for at least this many readings in a row
 * @param hysteresisKmph       the episode only ends when speed drops below limit - hysteresis
 * @param toleranceKmph        speedometer and GPS may differ this much ...
 * @param tolerancePercent     ... or this share of the speed, whichever is larger
 * @param maxGapSeconds        a longer gap between readings breaks the episode (continuity cannot be claimed)
 * @param windowPings          how many pings (2-3) the GPS speed is worked out over
 * @param requireGpsAgreement  when true, a reading only counts as over the limit if the GPS does not contradict it
 * @param mismatchPings        this many disagreeing readings in a row raise SPEED_SENSOR_MISMATCH
 */
public record SpeedRule(
        String scope,
        double limitKmph,
        long minDurationSeconds,
        int minConsecutivePings,
        double hysteresisKmph,
        double toleranceKmph,
        double tolerancePercent,
        long maxGapSeconds,
        int windowPings,
        boolean requireGpsAgreement,
        int mismatchPings) {

    public static SpeedRule defaults() {
        return new SpeedRule("default", 60, 60, 3, 5, 10, 0.15, 180, 3, false, 3);
    }

    /** How far apart speedometer and GPS may be at the given speed. */
    public double tolerance(double speedKmph) {
        return Math.max(toleranceKmph, tolerancePercent * speedKmph);
    }

    public SpeedRule withScope(String newScope) {
        return new SpeedRule(newScope, limitKmph, minDurationSeconds, minConsecutivePings, hysteresisKmph, toleranceKmph,
                tolerancePercent, maxGapSeconds, windowPings, requireGpsAgreement, mismatchPings);
    }
}
