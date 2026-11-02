package com.srm.gps.processing.speed;

import java.time.LocalTime;

/**
 * How a speed rule is applied at one reading, and how loud a confirmed over-speed is. The rule (per vehicle / company /
 * default) decides whether there is an episode; the policy adjusts the limit to the place and the hour, and grades it.
 *
 * Limit at a reading = the rule's limit, lowered to
 *   - the speed limit of the strictest geofence the reading is inside (a plant's 20 km/h, a school zone), and
 *   - minus {@code nightStricterKmph} between {@code nightStartHour} and {@code nightEndHour} (local time).
 *
 * Bands, by how far over the (adjusted) limit the episode's top speed went:
 *   WARNING  over the limit, under limit + breachOverKmph
 *   BREACH   limit + breachOverKmph up to limit + severeOverKmph
 *   SEVERE   above limit + severeOverKmph
 *   SUSPECT  the GPS contradicted the speedometer (faulty or tampered sensor): a device-health matter, not a driver one,
 *            when {@code suppressSensorSuspect}
 *
 * @param breachOverKmph         km/h over the limit where a warning becomes a breach (10)
 * @param severeOverKmph         km/h over the limit where a breach becomes severe (25)
 * @param suppressSensorSuspect  grade SENSOR_SUSPECT episodes as SUSPECT (low, no driver alert) instead of by speed
 * @param nightStricterKmph      the limit is this much lower at night; 0 = off
 * @param nightStartHour         night starts at this hour (22)
 * @param nightEndHour           ... and ends at this hour (5)
 */
public record SpeedPolicy(double breachOverKmph, double severeOverKmph, boolean suppressSensorSuspect, double nightStricterKmph,
                          int nightStartHour, int nightEndHour) {

    public enum Band { WARNING, BREACH, SEVERE, SUSPECT }

    public SpeedPolicy {
        if (breachOverKmph < 0 || severeOverKmph < breachOverKmph) {
            throw new IllegalArgumentException("Bands must rise: 0 <= breach <= severe");
        }
        if (nightStricterKmph < 0 || nightStartHour < 0 || nightStartHour > 23 || nightEndHour < 0 || nightEndHour > 23) {
            throw new IllegalArgumentException("Night hours are 0-23 and the night reduction is not negative");
        }
    }

    public static SpeedPolicy defaults() {
        return new SpeedPolicy(10, 25, true, 0, 22, 5);
    }

    /**
     * The rule to judge this reading by.
     *
     * @param zoneLimitKmph  the strictest speed limit of the geofences the reading is inside, or null
     * @param zoneCode       that geofence's code (shown in the event)
     * @param localTime      the reading's local time of day
     */
    public SpeedRule effective(SpeedRule rule, Integer zoneLimitKmph, String zoneCode, LocalTime localTime) {
        double limit = rule.limitKmph();
        String scope = rule.scope();
        if (zoneLimitKmph != null && zoneLimitKmph < limit) {
            limit = zoneLimitKmph;
            scope = scope + "+zone:" + zoneCode;
        }
        if (nightStricterKmph > 0 && night(localTime)) {
            limit = Math.max(5, limit - nightStricterKmph);
            scope = scope + "+night";
        }
        if (limit == rule.limitKmph()) {
            return rule;
        }
        return new SpeedRule(scope, limit, rule.minDurationSeconds(), rule.minConsecutivePings(), rule.hysteresisKmph(),
                rule.toleranceKmph(), rule.tolerancePercent(), rule.maxGapSeconds(), rule.windowPings(), rule.requireGpsAgreement(),
                rule.mismatchPings());
    }

    public boolean night(LocalTime t) {
        if (nightStartHour == nightEndHour) {
            return false;
        }
        int h = t.getHour();
        return nightStartHour < nightEndHour ? h >= nightStartHour && h < nightEndHour : h >= nightStartHour || h < nightEndHour;
    }

    /** The band of an episode that went {@code excessKmph} over its limit, with the GPS verdict. */
    public Band band(double excessKmph, String verdict) {
        if (suppressSensorSuspect && "SENSOR_SUSPECT".equals(verdict)) {
            return Band.SUSPECT;
        }
        if (excessKmph >= severeOverKmph) {
            return Band.SEVERE;
        }
        if (excessKmph >= breachOverKmph) {
            return Band.BREACH;
        }
        return Band.WARNING;
    }
}
