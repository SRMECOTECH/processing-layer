package com.srm.gps.processing.rule;

/**
 * Straight-line ("as the crow flies") distance between two GPS points.
 * Delegates to {@link DistanceCalculator}, the Traccar port, so every part of the platform measures the same way.
 */
public final class GeoDistanceCalculator {

    private GeoDistanceCalculator() {
    }

    public static double distanceInMeters(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude) {
        return DistanceCalculator.distance(fromLatitude, fromLongitude, toLatitude, toLongitude);
    }
}
