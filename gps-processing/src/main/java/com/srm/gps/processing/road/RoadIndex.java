package com.srm.gps.processing.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the road under a reading: the nearest stored road piece within {@code maxDistanceMeters}, from a grid of
 * 0.01-degree cells (about 1.1 km) holding every line part of every piece that crosses them. A lookup checks the
 * reading's cell and its 8 neighbours, so it costs the same with ten pieces or a hundred thousand.
 *
 * Immutable: the directory builds a new index when the library changes and swaps it in.
 */
public final class RoadIndex {

    static final double CELL_DEGREES = 0.01;
    private static final double METERS_PER_DEGREE = 111_320.0;

    /** The road under a reading and how far the reading is from its line. */
    public record Match(RoadSegment road, double distanceMeters) {
    }

    private record Part(RoadSegment road, double lat1, double lon1, double lat2, double lon2) {
    }

    private final Map<Long, List<Part>> cells = new HashMap<>();
    private final int roads;

    public RoadIndex(List<RoadSegment> segments) {
        for (RoadSegment road : segments) {
            List<double[]> points = road.points();
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                Part part = new Part(road, a[0], a[1], b[0], b[1]);
                int rowFrom = cell(Math.min(a[0], b[0]));
                int rowTo = cell(Math.max(a[0], b[0]));
                int colFrom = cell(Math.min(a[1], b[1]));
                int colTo = cell(Math.max(a[1], b[1]));
                for (int row = rowFrom; row <= rowTo; row++) {
                    for (int col = colFrom; col <= colTo; col++) {
                        cells.computeIfAbsent(key(row, col), k -> new ArrayList<>()).add(part);
                    }
                }
            }
        }
        this.roads = segments.size();
    }

    public static RoadIndex empty() {
        return new RoadIndex(List.of());
    }

    public int size() {
        return roads;
    }

    /** The nearest road within maxDistanceMeters of the point, or null. */
    public Match locate(double latitude, double longitude, double maxDistanceMeters) {
        int row = cell(latitude);
        int col = cell(longitude);
        Part best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                List<Part> parts = cells.get(key(row + dr, col + dc));
                if (parts == null) {
                    continue;
                }
                for (Part part : parts) {
                    double d = distanceToPart(latitude, longitude, part);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = part;
                    }
                }
            }
        }
        return best != null && bestDistance <= maxDistanceMeters ? new Match(best.road(), bestDistance) : null;
    }

    /** Metres from the point to the line part, on a local flat projection (exact enough within a few km). */
    private static double distanceToPart(double lat, double lon, Part p) {
        double cos = Math.cos(Math.toRadians(lat));
        double ax = (p.lon1() - lon) * cos * METERS_PER_DEGREE;
        double ay = (p.lat1() - lat) * METERS_PER_DEGREE;
        double bx = (p.lon2() - lon) * cos * METERS_PER_DEGREE;
        double by = (p.lat2() - lat) * METERS_PER_DEGREE;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
        double x = ax + t * dx;
        double y = ay + t * dy;
        return Math.sqrt(x * x + y * y);
    }

    private static int cell(double degrees) {
        return (int) Math.floor(degrees / CELL_DEGREES);
    }

    private static long key(int row, int col) {
        return ((long) row << 32) ^ (col & 0xffffffffL);
    }
}
