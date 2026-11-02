package com.srm.gps.processing.route;

import java.util.function.Function;

/**
 * The thresholds of route deviation, wrong direction, ETA and waypoints (docs/ROUTE_DEVIATION_ETA_DESIGN.md).
 * Built from a lookup so the pure engine needs no Spring: the modules pass gps.modules.route-deviation.* and
 * gps.modules.waypoints.*, the tests {@link #defaults()}. A key the lookup does not know keeps its default.
 */
public final class RouteSettings {

    /** Half-width of the corridor around the planned line where the road is fast (highway) or slow (town). */
    public final double corridorHighwayMeters;
    public final double corridorTownMeters;
    /** A maneuver Valhalla plans faster than this is "highway" for the corridor. */
    public final double highwaySpeedKmph;

    /** SUSPECT becomes a check after this many readings off the corridor, this long, this far driven off the line... */
    public final int suspectReadings;
    public final long suspectSeconds;
    public final double suspectOffMeters;
    /** ...or at once beyond this distance from the line. */
    public final double farOffMeters;
    /** Back inside the corridor for this many readings: the suspicion was drift or a parallel lane. */
    public final int driftForgiveReadings;
    /** Back inside for this many readings: an open deviation is closed. */
    public final int rejoinReadings;
    /** A reading implying more than this from the previous one is a GPS jump and is ignored. */
    public final double maxJumpKmph;
    /** While off route, the re-plan is asked again this often (added time, driving away from the destination). */
    public final long recheckSeconds;
    /** A check with no answer after this long (in reading time) is asked again. */
    public final long checkTimeoutSeconds;
    /** Inside the origin area nothing is judged: the truck has not left yet. */
    public final double originClearMeters;

    public final int wrongWayReadings;
    /** Progress along the plan down by this much while moving: wrong direction... */
    public final double wrongWayBackMeters;
    /** ...or heading this far from the road's direction for wrongWayReadings readings, with at least wrongWayHeadingBackMeters lost. */
    public final double wrongWayHeadingDegrees;
    public final double wrongWayHeadingBackMeters;
    public final double wrongWayMinKmph;
    /** Off the plan: the re-plan's remaining distance grew by this much since the last check while moving. */
    public final double offPlanWrongWayMeters;

    /** A re-plan within this factor of the planned remaining time (or this many seconds more) is an alternate road. */
    public final double alternateTimeFactor;
    public final long alternateExtraSeconds;
    public final long highAddedMinutes;
    public final long criticalAddedMinutes;

    /** The live pace (actual / planned driving time) is clamped to this range and learned over this horizon. */
    public final double paceMin;
    public final double paceMax;
    public final double initialPace;
    public final long paceHorizonSeconds;
    public final long paceSampleSeconds;
    public final double paceSampleMeters;
    /** The starting pace counts as this much driving: after it, the truck's own pace and the prior weigh the same. */
    public final long pacePriorSeconds;

    /** Driver-hour rule: a rest of restSeconds after every restAfterDrivingSeconds of driving still ahead. */
    public final long restAfterDrivingSeconds;
    public final long restSeconds;

    /** The shown ETA moves at most this many seconds per second towards the new estimate... */
    public final double etaMaxShiftRate;
    /** ...unless it is off by more than this (a deviation, a long stop): then it jumps. */
    public final long etaJumpSeconds;
    /** ETA_CHANGED when the shown ETA moved this far from the last one announced, or this share of the time left if more. */
    public final long etaAnnounceSeconds;
    public final double etaAnnounceShare;

    /** Radius of a destination given as a point (a geofence destination uses its own shape). */
    public final double arrivalRadiusMeters;
    /** Inside a waypoint this long is an arrival; less is a drive-through. */
    public final long waypointMinStaySeconds;
    /** A waypoint never entered is missed once the truck is this far past it along the plan. */
    public final double waypointMissMeters;
    /** Radius of a waypoint given as a point. */
    public final double waypointRadiusMeters;

    private RouteSettings(Function<String, String> lookup) {
        corridorHighwayMeters = number(lookup, "corridor-highway-meters", 150);
        corridorTownMeters = number(lookup, "corridor-town-meters", 80);
        highwaySpeedKmph = number(lookup, "highway-speed-kmph", 45);
        suspectReadings = (int) number(lookup, "suspect-readings", 3);
        suspectSeconds = (long) number(lookup, "suspect-minutes", 5) * 60;
        suspectOffMeters = number(lookup, "suspect-off-meters", 1_000);
        farOffMeters = number(lookup, "far-off-meters", 2_000);
        driftForgiveReadings = (int) number(lookup, "drift-forgive-readings", 2);
        rejoinReadings = (int) number(lookup, "rejoin-readings", 3);
        maxJumpKmph = number(lookup, "max-jump-kmph", 150);
        recheckSeconds = (long) number(lookup, "recheck-minutes", 10) * 60;
        checkTimeoutSeconds = (long) number(lookup, "check-timeout-minutes", 10) * 60;
        originClearMeters = number(lookup, "origin-clear-meters", 500);
        wrongWayReadings = (int) number(lookup, "wrong-way-readings", 3);
        wrongWayBackMeters = number(lookup, "wrong-way-back-meters", 1_000);
        wrongWayHeadingDegrees = number(lookup, "wrong-way-heading-degrees", 135);
        wrongWayHeadingBackMeters = number(lookup, "wrong-way-heading-back-meters", 300);
        wrongWayMinKmph = number(lookup, "wrong-way-min-kmph", 10);
        offPlanWrongWayMeters = number(lookup, "off-plan-wrong-way-meters", 2_000);
        alternateTimeFactor = number(lookup, "alternate-time-factor", 1.10);
        alternateExtraSeconds = (long) number(lookup, "alternate-extra-minutes", 15) * 60;
        highAddedMinutes = (long) number(lookup, "high-added-minutes", 30);
        criticalAddedMinutes = (long) number(lookup, "critical-added-minutes", 90);
        paceMin = number(lookup, "pace-min", 0.6);
        paceMax = number(lookup, "pace-max", 2.5);
        initialPace = number(lookup, "initial-pace", 1.0);
        paceHorizonSeconds = (long) number(lookup, "pace-horizon-minutes", 60) * 60;
        paceSampleSeconds = (long) number(lookup, "pace-sample-minutes", 5) * 60;
        paceSampleMeters = number(lookup, "pace-sample-meters", 500);
        pacePriorSeconds = (long) number(lookup, "pace-prior-minutes", 60) * 60;
        restAfterDrivingSeconds = (long) (number(lookup, "rest-after-driving-hours", 4.5) * 3600);
        restSeconds = (long) number(lookup, "rest-minutes", 30) * 60;
        etaMaxShiftRate = number(lookup, "eta-max-shift-per-minute", 2.0);
        etaJumpSeconds = (long) number(lookup, "eta-jump-minutes", 30) * 60;
        etaAnnounceSeconds = (long) number(lookup, "eta-announce-minutes", 30) * 60;
        etaAnnounceShare = number(lookup, "eta-announce-percent", 10) / 100.0;
        arrivalRadiusMeters = number(lookup, "arrival-radius-meters", 300);
        waypointMinStaySeconds = (long) number(lookup, "waypoint-min-stay-minutes", 2) * 60;
        waypointMissMeters = number(lookup, "waypoint-miss-meters", 2_000);
        waypointRadiusMeters = number(lookup, "waypoint-radius-meters", 300);
        if (corridorTownMeters <= 0 || corridorHighwayMeters <= 0 || paceMin <= 0 || paceMax < paceMin
                || initialPace < paceMin || initialPace > paceMax || alternateTimeFactor < 1) {
            throw new IllegalArgumentException("Route settings out of range: corridors > 0, 0 < pace-min <= initial-pace <= pace-max, "
                    + "alternate-time-factor >= 1");
        }
    }

    public static RouteSettings defaults() {
        return new RouteSettings(key -> null);
    }

    /** @param lookup the value for a key such as "corridor-highway-meters", or null for the default */
    public static RouteSettings from(Function<String, String> lookup) {
        return new RouteSettings(lookup);
    }

    private static double number(Function<String, String> lookup, String key, double fallback) {
        String value = lookup.apply(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Route setting " + key + " is not a number: " + value, e);
        }
    }
}
