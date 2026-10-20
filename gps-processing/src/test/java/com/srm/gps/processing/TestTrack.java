package com.srm.gps.processing;

import com.srm.gps.processing.rule.DistanceCalculator;

import java.time.Instant;

/** Builds positions a known number of metres apart, on the same sphere the platform measures with. */
public final class TestTrack {

    public static final double BASE_LATITUDE = 22.80;
    public static final double BASE_LONGITUDE = 86.20;
    public static final Instant START = Instant.parse("2026-10-20T06:00:00Z");

    private TestTrack() {
    }

    /** {latitude, longitude} of a point east/north of the base point. */
    public static double[] point(double eastMeters, double northMeters) {
        double metersPerDegree = DistanceCalculator.EQUATORIAL_EARTH_RADIUS_METERS * Math.PI / 180;
        return new double[]{
                BASE_LATITUDE + northMeters / metersPerDegree,
                BASE_LONGITUDE + eastMeters / (metersPerDegree * Math.cos(Math.toRadians(BASE_LATITUDE)))};
    }

    public static Instant at(long seconds) {
        return START.plusSeconds(seconds);
    }
}
