package com.srm.gps.processing.geofence;

import com.srm.gps.processing.rule.DistanceCalculator;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One geofence: a circle (centre + radius) or a polygon (its corners in order, closed implicitly). Immutable, built
 * once when the fences are loaded and shared by every thread.
 *
 * Inside tests:
 *   circle   flat-earth (equirectangular) distance to the centre against the radius:
 *              dx = Δlon · cos(centre lat) · 111,320 m,  dy = Δlat · 111,320 m,  inside when dx² + dy² ≤ r²
 *            no square root and no trigonometry per call (cos of the centre latitude is worked out once). Within
 *            0.2 % of the radius of the haversine, well under the scatter of a GPS standing at a plant gate.
 *   polygon  ray casting (even-odd): a ray from the point towards the east crosses the border an odd number of times
 *            when the point is inside. The half-open test (yi > y) != (yj > y) counts a ray that passes exactly
 *            through a corner once, and the bounding box throws out clear misses before any edge is looked at.
 *
 * For a polygon, centre and radius are the average of the corners and the distance to the farthest corner: they
 * give the "escape" distance (see {@link GeofenceTracker}) and the circle shown on a map.
 */
public final class Geofence {

    public enum Shape { CIRCLE, POLYGON }

    static final double METERS_PER_DEGREE = 111_320;

    private final long id;
    private final String code;
    private final String name;
    private final String role;
    private final Shape shape;
    private final double latitude;
    private final double longitude;
    private final double radiusMeters;
    private final double[] polygonLatitudes;
    private final double[] polygonLongitudes;
    private final Integer dwellAlertMinutes;
    private final Integer speedLimitKmph;

    private final double cosLatitude;
    private final double radiusSquared;
    private final double minLatitude;
    private final double maxLatitude;
    private final double minLongitude;
    private final double maxLongitude;

    private Geofence(long id, String code, String name, String role, Shape shape, double latitude, double longitude,
                     double radiusMeters, double[] polygonLatitudes, double[] polygonLongitudes, Integer dwellAlertMinutes,
                     Integer speedLimitKmph) {
        this.id = id;
        this.code = Objects.requireNonNull(code, "code");
        this.name = name == null ? code : name;
        this.role = role == null ? "DESTINATION" : role;
        this.shape = shape;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.polygonLatitudes = polygonLatitudes;
        this.polygonLongitudes = polygonLongitudes;
        this.dwellAlertMinutes = dwellAlertMinutes;
        this.speedLimitKmph = speedLimitKmph;
        // a corrupt latitude near a pole must not turn the box infinite
        this.cosLatitude = Math.max(Math.cos(Math.toRadians(latitude)), 0.01);
        this.radiusSquared = radiusMeters * radiusMeters;
        if (shape == Shape.POLYGON) {
            this.minLatitude = Arrays.stream(polygonLatitudes).min().orElseThrow();
            this.maxLatitude = Arrays.stream(polygonLatitudes).max().orElseThrow();
            this.minLongitude = Arrays.stream(polygonLongitudes).min().orElseThrow();
            this.maxLongitude = Arrays.stream(polygonLongitudes).max().orElseThrow();
        } else {
            double dLat = radiusMeters / METERS_PER_DEGREE;
            double dLon = dLat / cosLatitude;
            this.minLatitude = latitude - dLat;
            this.maxLatitude = latitude + dLat;
            this.minLongitude = longitude - dLon;
            this.maxLongitude = longitude + dLon;
        }
    }

    public static Geofence circle(long id, String code, String name, String role, double latitude, double longitude,
                                  double radiusMeters, Integer dwellAlertMinutes, Integer speedLimitKmph) {
        if (!(radiusMeters > 0)) {
            throw new IllegalArgumentException("A circular geofence needs a radius above 0 m: " + code);
        }
        return new Geofence(id, code, name, role, Shape.CIRCLE, latitude, longitude, radiusMeters, null, null,
                dwellAlertMinutes, speedLimitKmph);
    }

    /** @param corners [latitude, longitude] pairs in order around the border; at least three */
    public static Geofence polygon(long id, String code, String name, String role, List<double[]> corners,
                                   Integer dwellAlertMinutes, Integer speedLimitKmph) {
        if (corners == null || corners.size() < 3) {
            throw new IllegalArgumentException("A polygon geofence needs at least three corners: " + code);
        }
        double[] lats = new double[corners.size()];
        double[] lons = new double[corners.size()];
        double sumLat = 0;
        double sumLon = 0;
        for (int i = 0; i < corners.size(); i++) {
            lats[i] = corners.get(i)[0];
            lons[i] = corners.get(i)[1];
            sumLat += lats[i];
            sumLon += lons[i];
        }
        double centreLat = sumLat / lats.length;
        double centreLon = sumLon / lons.length;
        double farthest = 0;
        for (int i = 0; i < lats.length; i++) {
            farthest = Math.max(farthest, DistanceCalculator.distance(centreLat, centreLon, lats[i], lons[i]));
        }
        return new Geofence(id, code, name, role, Shape.POLYGON, centreLat, centreLon, Math.max(farthest, 1), lats, lons,
                dwellAlertMinutes, speedLimitKmph);
    }

    /** True when the point is inside (or on the border of) the fence. */
    public boolean contains(double lat, double lon) {
        if (lat < minLatitude || lat > maxLatitude || lon < minLongitude || lon > maxLongitude) {
            return false;
        }
        if (shape == Shape.CIRCLE) {
            double dy = (lat - latitude) * METERS_PER_DEGREE;
            double dx = (lon - longitude) * METERS_PER_DEGREE * cosLatitude;
            return dx * dx + dy * dy <= radiusSquared;
        }
        boolean inside = false;
        for (int i = 0, j = polygonLatitudes.length - 1; i < polygonLatitudes.length; j = i++) {
            double yi = polygonLatitudes[i];
            double yj = polygonLatitudes[j];
            if ((yi > lat) != (yj > lat)) {
                double xi = polygonLongitudes[i];
                double xj = polygonLongitudes[j];
                double crossingLon = xi + (lat - yi) * (xj - xi) / (yj - yi);
                if (lon < crossingLon) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** Metres from the centre (flat earth around the centre; fine at fence scale). */
    public double distanceFromCentreMeters(double lat, double lon) {
        double dy = (lat - latitude) * METERS_PER_DEGREE;
        double dx = (lon - longitude) * METERS_PER_DEGREE * cosLatitude;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Far enough out that it cannot be scatter: more than {@code factor} radii from the centre. */
    public boolean beyondEscape(double lat, double lon, double factor) {
        return distanceFromCentreMeters(lat, lon) > factor * radiusMeters;
    }

    /**
     * Where along the straight move (lat1, lon1) -> (lat2, lon2) the fence was crossed, as a fraction 0..1 of the move,
     * or -1 when the move does not touch the fence. Used for fences passed between two readings with no reading inside.
     */
    public double crossingFraction(double lat1, double lon1, double lat2, double lon2) {
        if (Math.max(lat1, lat2) < minLatitude || Math.min(lat1, lat2) > maxLatitude
                || Math.max(lon1, lon2) < minLongitude || Math.min(lon1, lon2) > maxLongitude) {
            return -1;
        }
        // local metres around the centre: x east, y north
        double ax = (lon1 - longitude) * METERS_PER_DEGREE * cosLatitude;
        double ay = (lat1 - latitude) * METERS_PER_DEGREE;
        double bx = (lon2 - longitude) * METERS_PER_DEGREE * cosLatitude;
        double by = (lat2 - latitude) * METERS_PER_DEGREE;
        double vx = bx - ax;
        double vy = by - ay;
        double lengthSquared = vx * vx + vy * vy;
        double t = lengthSquared == 0 ? 0 : Math.clamp(-(ax * vx + ay * vy) / lengthSquared, 0, 1);
        if (shape == Shape.CIRCLE) {
            double px = ax + t * vx;
            double py = ay + t * vy;
            return px * px + py * py <= radiusSquared ? t : -1;
        }
        // polygon: the first border edge the move cuts
        double best = -1;
        for (int i = 0, j = polygonLatitudes.length - 1; i < polygonLatitudes.length; j = i++) {
            if (DistanceCalculator.segmentsIntersect(lon1, lat1, lon2, lat2,
                    polygonLongitudes[j], polygonLatitudes[j], polygonLongitudes[i], polygonLatitudes[i])) {
                double f = intersectionFraction(lon1, lat1, lon2, lat2,
                        polygonLongitudes[j], polygonLatitudes[j], polygonLongitudes[i], polygonLatitudes[i]);
                if (best < 0 || f < best) {
                    best = f;
                }
            }
        }
        return best;
    }

    private static double intersectionFraction(double x1, double y1, double x2, double y2,
                                               double x3, double y3, double x4, double y4) {
        double denominator = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (denominator == 0) {
            return 0;
        }
        return Math.clamp(((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denominator, 0, 1);
    }

    public long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    /** ORIGIN, DESTINATION, YARD, CHECKPOINT or ZONE. */
    public String role() {
        return role;
    }

    public Shape shape() {
        return shape;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public double radiusMeters() {
        return radiusMeters;
    }

    /** This fence's own dwell limit, or null to use the role's default. */
    public Integer dwellAlertMinutes() {
        return dwellAlertMinutes;
    }

    /** The speed limit inside this fence (a plant's 20 km/h), or null for none. */
    public Integer speedLimitKmph() {
        return speedLimitKmph;
    }

    double minLatitude() {
        return minLatitude;
    }

    double maxLatitude() {
        return maxLatitude;
    }

    double minLongitude() {
        return minLongitude;
    }

    double maxLongitude() {
        return maxLongitude;
    }

    @Override
    public String toString() {
        return shape + " " + code + " (" + role + ", id " + id + ")";
    }
}
