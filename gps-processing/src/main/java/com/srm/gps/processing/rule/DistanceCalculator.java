package com.srm.gps.processing.rule;

/**
 * Port of Traccar's {@code org.traccar.helper.DistanceCalculator} (studied in rev_engineer/traccar-rnd).
 *
 * Great-circle (haversine) distance with the WGS-84 equatorial radius, exactly as Traccar computes it,
 * so our distances, trips and odometers line up with a Traccar server fed the same positions.
 * (The equatorial radius reads about 0.1% longer than the mean radius 6,371 km: 1 m per km.)
 *
 * Also ported: distance from a point to a segment (corridors, route deviation) and segment intersection
 * (a straight move between two readings that crosses a geofence edge).
 */
public final class DistanceCalculator {

    public static final double EQUATORIAL_EARTH_RADIUS_METERS = 6_378_137.0;
    private static final double DEG_TO_RAD = Math.PI / 180;
    private static final double METERS_PER_DEGREE_LATITUDE = 111_320;

    private DistanceCalculator() {
    }

    /** Haversine distance in metres. */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLon = (lon2 - lon1) * DEG_TO_RAD;
        double dLat = (lat2 - lat1) * DEG_TO_RAD;
        double sinDLat = Math.sin(dLat / 2);
        double sinDLon = Math.sin(dLon / 2);
        double a = sinDLat * sinDLat
                + Math.cos(lat1 * DEG_TO_RAD) * Math.cos(lat2 * DEG_TO_RAD) * sinDLon * sinDLon;
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EQUATORIAL_EARTH_RADIUS_METERS * c;
    }

    /**
     * Shortest distance in metres from a point to the segment (lat1,lon1)-(lat2,lon2):
     * Heron's formula on the triangle of the three haversine sides, or the distance to the nearer end
     * when the point lies beyond either end of the segment.
     */
    public static double distanceToLine(double pointLat, double pointLon, double lat1, double lon1, double lat2, double lon2) {
        double d0 = distance(pointLat, pointLon, lat1, lon1);
        double d1 = distance(lat1, lon1, lat2, lon2);
        double d2 = distance(lat2, lon2, pointLat, pointLon);
        double d0Squared = d0 * d0;
        double d1Squared = d1 * d1;
        double d2Squared = d2 * d2;
        if (d0Squared > d1Squared + d2Squared) {
            return d2;
        }
        if (d2Squared > d1Squared + d0Squared) {
            return d0;
        }
        double halfPerimeter = (d0 + d1 + d2) * 0.5;
        double area = Math.sqrt(Math.max(0, halfPerimeter * (halfPerimeter - d0) * (halfPerimeter - d1) * (halfPerimeter - d2)));
        return d1 == 0 ? d0 : 2 * area / d1;
    }

    /** True when segment A (a1-a2) and segment B (b1-b2) cross or touch (planar test, fine for short segments). */
    public static boolean segmentsIntersect(double ax1, double ay1, double ax2, double ay2,
                                            double bx1, double by1, double bx2, double by2) {
        double d1 = direction(bx1, by1, bx2, by2, ax1, ay1);
        double d2 = direction(bx1, by1, bx2, by2, ax2, ay2);
        double d3 = direction(ax1, ay1, ax2, ay2, bx1, by1);
        double d4 = direction(ax1, ay1, ax2, ay2, bx2, by2);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        if (d1 == 0 && onSegment(bx1, by1, bx2, by2, ax1, ay1)) {
            return true;
        }
        if (d2 == 0 && onSegment(bx1, by1, bx2, by2, ax2, ay2)) {
            return true;
        }
        if (d3 == 0 && onSegment(ax1, ay1, ax2, ay2, bx1, by1)) {
            return true;
        }
        return d4 == 0 && onSegment(ax1, ay1, ax2, ay2, bx2, by2);
    }

    /** Degrees of latitude that span the given metres. */
    public static double latitudeDelta(double meters) {
        return meters / METERS_PER_DEGREE_LATITUDE;
    }

    /** Degrees of longitude that span the given metres at the given latitude. */
    public static double longitudeDelta(double meters, double latitude) {
        return meters / (METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude)));
    }

    /** Initial bearing (0 = north, 90 = east) from the first point to the second, in degrees 0..360. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = lat1 * DEG_TO_RAD;
        double phi2 = lat2 * DEG_TO_RAD;
        double dLon = (lon2 - lon1) * DEG_TO_RAD;
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360) % 360;
    }

    private static double direction(double ax, double ay, double bx, double by, double cx, double cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }

    private static boolean onSegment(double ax, double ay, double bx, double by, double px, double py) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx)
                && Math.min(ay, by) <= py && py <= Math.max(ay, by);
    }
}
