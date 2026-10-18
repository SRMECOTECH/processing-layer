package com.srm.gps.processing.rule;

/** Straight-line ("as the crow flies") distance between two GPS points, using the haversine formula. */
public final class GeoDistanceCalculator {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private GeoDistanceCalculator() {
    }

    public static double distanceInMeters(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude) {
        double latitudeDifference = Math.toRadians(toLatitude - fromLatitude);
        double longitudeDifference = Math.toRadians(toLongitude - fromLongitude);

        double a = Math.sin(latitudeDifference / 2) * Math.sin(latitudeDifference / 2)
                + Math.cos(Math.toRadians(fromLatitude)) * Math.cos(Math.toRadians(toLatitude))
                * Math.sin(longitudeDifference / 2) * Math.sin(longitudeDifference / 2);

        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
