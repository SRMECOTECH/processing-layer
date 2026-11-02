package com.srm.gps.processing.route;

import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.rule.DistanceCalculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One planned route you can project a reading onto, in microseconds: the road line from the routing engine, the
 * distance done at every vertex (chainage), the planned driving seconds at every vertex (the engine's own truck speed per
 * road, not one average), a corridor half-width per segment, the stops on the way and the destination area.
 *
 * Projection uses a uniform grid like the geofence index: every segment is filed in each 0.01 deg cell (about 1.1 km)
 * its bounding box touches. A reading looks at its own cell and the eight around it; only a reading more than a cell
 * away from the whole line falls back to a scan of every segment (it is off route anyway). Where the line runs over the
 * same road twice (out and back), the candidate nearest the previous chainage wins, so progress never jumps.
 */
public final class RoutePlan {

    static final double CELL_DEGREES = 0.01;
    private static final double METERS_PER_DEGREE = 111_320.0;
    /** Candidates this close to the best one are "the same road": continuity picks among them. */
    private static final double SAME_ROAD_METERS = 30;

    /** A stop on the way: a geofence the route crosses (FENCE) or a waypoint someone planned (MANUAL). */
    public record Stop(int seq, String name, String kind, Long placeId, Geofence area, double chainageMeters, int stayMinutes) {

        public boolean manual() {
            return "MANUAL".equals(kind);
        }
    }

    /** One maneuver of the engine's answer: the vertices from..to take this many seconds of driving. */
    public record Timing(int fromIndex, int toIndex, double seconds) {
    }

    /**
     * Where a reading falls on the plan.
     *
     * @param crossTrackMeters distance from the line
     * @param chainageMeters   distance along the plan to the nearest point of the line
     * @param bearing          direction of the road there, degrees from north
     * @param corridorMeters   half-width of the corridor there
     */
    public record Projection(int segment, double crossTrackMeters, double chainageMeters, double bearing, double corridorMeters) {
    }

    private final long id;
    private final int version;
    private final double offsetMeters;
    private final double initialPace;
    private final double[] lat;
    private final double[] lon;
    private final double[] cumMeters;
    private final double[] cumSeconds;
    private final double[] corridor;
    private final List<Stop> stops;
    private final Geofence origin;
    private final Geofence destination;
    private final Map<Long, int[]> grid;

    private RoutePlan(long id, int version, double offsetMeters, double initialPace, double[] lat, double[] lon, double[] cumMeters,
                      double[] cumSeconds, double[] corridor, Geofence origin, Geofence destination) {
        this.id = id;
        this.version = version;
        this.offsetMeters = offsetMeters;
        this.initialPace = initialPace;
        this.lat = lat;
        this.lon = lon;
        this.cumMeters = cumMeters;
        this.cumSeconds = cumSeconds;
        this.corridor = corridor;
        this.origin = origin;
        this.destination = destination;
        this.grid = buildGrid(lat, lon);
        this.stops = new ArrayList<>();
    }

    /**
     * @param points       the line, {latitude, longitude}, at least two
     * @param timings      the engine's maneuvers; segments outside every maneuver get the average speed of totalSeconds
     * @param totalSeconds the engine's planned driving time for the whole line
     * @param stops        stops on the way; their chainage is worked out here from their centres, in order
     * @param origin       where the trip starts (nothing is judged inside it), or null
     * @param destination  the destination area (arrival = a reading inside it)
     * @param offsetMeters distance already done before this version of the plan (a re-plan starts mid-trip)
     */
    public static RoutePlan of(long id, int version, List<double[]> points, List<Timing> timings, double totalSeconds, List<Stop> stops,
                               Geofence origin, Geofence destination, double offsetMeters, double initialPace, RouteSettings settings) {
        List<double[]> line = new ArrayList<>(points.size());
        // the engine's vertex i is our vertex indexOf[i] once repeated points are dropped
        int[] indexOf = new int[points.size()];
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            if (line.isEmpty() || line.get(line.size() - 1)[0] != p[0] || line.get(line.size() - 1)[1] != p[1]) {
                line.add(p);
            }
            indexOf[i] = line.size() - 1;
        }
        if (line.size() < 2) {
            throw new IllegalArgumentException("A route needs at least two different points");
        }
        if (destination == null) {
            throw new IllegalArgumentException("A route needs a destination area");
        }
        int n = line.size();
        double[] lat = new double[n];
        double[] lon = new double[n];
        double[] cum = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = line.get(i)[0];
            lon[i] = line.get(i)[1];
            if (i > 0) {
                cum[i] = cum[i - 1] + DistanceCalculator.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            }
        }
        double length = cum[n - 1];
        double averageSpeed = totalSeconds > 0 && length > 0 ? length / totalSeconds : 40 / 3.6;
        double[] segmentSeconds = new double[n - 1];
        boolean[] timed = new boolean[n - 1];
        double[] corridor = new double[n - 1];
        Arrays.fill(corridor, -1);
        int lastIndex = points.size() - 1;
        for (Timing t : timings == null ? List.<Timing>of() : timings) {
            int from = indexOf[Math.max(0, Math.min(lastIndex, t.fromIndex()))];
            int to = indexOf[Math.max(0, Math.min(lastIndex, t.toIndex()))];
            if (to <= from) {
                continue;
            }
            double meters = cum[to] - cum[from];
            double speedKmph = t.seconds() > 0 ? meters / t.seconds() * 3.6 : 0;
            double width = speedKmph >= settings.highwaySpeedKmph ? settings.corridorHighwayMeters : settings.corridorTownMeters;
            for (int s = from; s < to; s++) {
                double share = meters > 0 ? (cum[s + 1] - cum[s]) / meters : 1.0 / (to - from);
                segmentSeconds[s] = t.seconds() * share;
                timed[s] = true;
                corridor[s] = width;
            }
        }
        double[] cumSeconds = new double[n];
        for (int s = 0; s < n - 1; s++) {
            if (!timed[s]) {
                segmentSeconds[s] = (cum[s + 1] - cum[s]) / averageSpeed;
            }
            if (corridor[s] < 0) {
                corridor[s] = averageSpeed * 3.6 >= settings.highwaySpeedKmph ? settings.corridorHighwayMeters : settings.corridorTownMeters;
            }
            cumSeconds[s + 1] = cumSeconds[s] + segmentSeconds[s];
        }
        RoutePlan plan = new RoutePlan(id, version, offsetMeters, initialPace, lat, lon, cum, cumSeconds, corridor, origin, destination);
        double last = Double.NaN;
        for (Stop stop : stops == null ? List.<Stop>of() : stops) {
            // a stop that already knows where the line meets it (a geofence's entry point) keeps it; others are placed
            // at the projection of their centre, when the line passes close enough
            double chainage = stop.chainageMeters();
            if (chainage < 0 || chainage > plan.lengthMeters()) {
                Projection p = plan.project(stop.area().latitude(), stop.area().longitude(), last);
                chainage = p.crossTrackMeters() <= stop.area().radiusMeters() + 2 * p.corridorMeters() ? p.chainageMeters() : -1;
            }
            if (chainage >= 0) {
                last = chainage;
            }
            plan.stops.add(new Stop(stop.seq(), stop.name(), stop.kind(), stop.placeId(), stop.area(), chainage, stop.stayMinutes()));
        }
        return plan;
    }

    /** @param lastChainage the chainage of the previous reading (NaN: none), to stay on the same pass of a road used twice */
    public Projection project(double latitude, double longitude, double lastChainage) {
        long row = (long) Math.floor(latitude / CELL_DEGREES);
        long col = (long) Math.floor(longitude / CELL_DEGREES);
        double k = Math.cos(Math.toRadians(latitude)) * METERS_PER_DEGREE;
        double[] out = new double[2];
        int best = -1;
        double bestCross = Double.MAX_VALUE;
        int[] candidates = new int[16];
        int count = 0;
        for (long r = row - 1; r <= row + 1; r++) {
            for (long c = col - 1; c <= col + 1; c++) {
                int[] segments = grid.get(key(r, c));
                if (segments == null) {
                    continue;
                }
                for (int s : segments) {
                    if (count == candidates.length) {
                        candidates = Arrays.copyOf(candidates, count * 2);
                    }
                    candidates[count++] = s;
                }
            }
        }
        if (count == 0) {
            candidates = new int[lat.length - 1];
            for (int s = 0; s < candidates.length; s++) {
                candidates[s] = s;
            }
            count = candidates.length;
        }
        double[] cross = new double[count];
        double[] chain = new double[count];
        for (int i = 0; i < count; i++) {
            segment(candidates[i], latitude, longitude, k, out);
            cross[i] = out[0];
            chain[i] = out[1];
            if (out[0] < bestCross) {
                bestCross = out[0];
                best = i;
            }
        }
        if (!Double.isNaN(lastChainage)) {
            double bestGap = Math.abs(chain[best] - lastChainage);
            for (int i = 0; i < count; i++) {
                if (cross[i] <= bestCross + SAME_ROAD_METERS && Math.abs(chain[i] - lastChainage) < bestGap) {
                    bestGap = Math.abs(chain[i] - lastChainage);
                    best = i;
                }
            }
        }
        int s = candidates[best];
        double bearing = DistanceCalculator.bearing(lat[s], lon[s], lat[s + 1], lon[s + 1]);
        return new Projection(s, cross[best], chain[best], bearing, corridor[s]);
    }

    /** Cross-track distance and chainage of a point against segment s, on a flat local frame (fine for road segments). */
    private void segment(int s, double latitude, double longitude, double k, double[] out) {
        double bx = (lon[s + 1] - lon[s]) * k;
        double by = (lat[s + 1] - lat[s]) * METERS_PER_DEGREE;
        double px = (longitude - lon[s]) * k;
        double py = (latitude - lat[s]) * METERS_PER_DEGREE;
        double len2 = bx * bx + by * by;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by) / len2));
        double dx = px - t * bx;
        double dy = py - t * by;
        out[0] = Math.sqrt(dx * dx + dy * dy);
        out[1] = cumMeters[s] + t * (cumMeters[s + 1] - cumMeters[s]);
    }

    /** Planned driving seconds from the start of this version to the given chainage. */
    public double secondsAt(double chainage) {
        int n = cumMeters.length;
        if (chainage <= 0) {
            return 0;
        }
        if (chainage >= cumMeters[n - 1]) {
            return cumSeconds[n - 1];
        }
        int i = Arrays.binarySearch(cumMeters, chainage);
        if (i >= 0) {
            return cumSeconds[i];
        }
        int hi = -i - 1;
        int lo = hi - 1;
        double f = (chainage - cumMeters[lo]) / (cumMeters[hi] - cumMeters[lo]);
        return cumSeconds[lo] + f * (cumSeconds[hi] - cumSeconds[lo]);
    }

    /** The point at a chainage, {latitude, longitude}. */
    public double[] pointAt(double chainage) {
        int n = cumMeters.length;
        if (chainage <= 0) {
            return new double[] {lat[0], lon[0]};
        }
        if (chainage >= cumMeters[n - 1]) {
            return new double[] {lat[n - 1], lon[n - 1]};
        }
        int i = Arrays.binarySearch(cumMeters, chainage);
        if (i >= 0) {
            return new double[] {lat[i], lon[i]};
        }
        int hi = -i - 1;
        int lo = hi - 1;
        double f = (chainage - cumMeters[lo]) / (cumMeters[hi] - cumMeters[lo]);
        return new double[] {lat[lo] + f * (lat[hi] - lat[lo]), lon[lo] + f * (lon[hi] - lon[lo])};
    }

    /** Planned stay at the stops still ahead of a chainage, in seconds. */
    public double staySecondsAhead(double chainage) {
        double seconds = 0;
        for (Stop stop : stops) {
            if (stop.chainageMeters() > chainage + 50) {
                seconds += stop.stayMinutes() * 60.0;
            }
        }
        return seconds;
    }

    /** The stop whose area holds the point, or null. */
    public Stop stopContaining(double latitude, double longitude) {
        for (Stop stop : stops) {
            if (stop.area().contains(latitude, longitude)) {
                return stop;
            }
        }
        return null;
    }

    public boolean atDestination(double latitude, double longitude) {
        return destination.contains(latitude, longitude);
    }

    /** Still inside the origin area (or within clearMeters of the start when the origin is a point). */
    public boolean atOrigin(double latitude, double longitude, double clearMeters) {
        if (origin != null && origin.contains(latitude, longitude)) {
            return true;
        }
        return version == 1 && offsetMeters == 0 && DistanceCalculator.distance(lat[0], lon[0], latitude, longitude) <= clearMeters;
    }

    public long id() {
        return id;
    }

    public int version() {
        return version;
    }

    public double offsetMeters() {
        return offsetMeters;
    }

    public double initialPace() {
        return initialPace;
    }

    public double lengthMeters() {
        return cumMeters[cumMeters.length - 1];
    }

    public double totalSeconds() {
        return cumSeconds[cumSeconds.length - 1];
    }

    public int size() {
        return lat.length;
    }

    public double latitude(int i) {
        return lat[i];
    }

    public double longitude(int i) {
        return lon[i];
    }

    public double chainageAt(int i) {
        return cumMeters[i];
    }

    public List<Stop> stops() {
        return List.copyOf(stops);
    }

    public Geofence origin() {
        return origin;
    }

    public Geofence destination() {
        return destination;
    }

    /** The line, thinned to at most maxPoints (every k-th vertex plus the last), for drawing. */
    public List<double[]> shape(int maxPoints) {
        int step = Math.max(1, (int) Math.ceil(lat.length / (double) Math.max(2, maxPoints)));
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < lat.length; i += step) {
            points.add(new double[] {lat[i], lon[i]});
        }
        if ((lat.length - 1) % step != 0) {
            points.add(new double[] {lat[lat.length - 1], lon[lat.length - 1]});
        }
        return points;
    }

    private static Map<Long, int[]> buildGrid(double[] lat, double[] lon) {
        Map<Long, int[]> cells = new HashMap<>();
        Map<Long, Integer> sizes = new HashMap<>();
        for (int s = 0; s < lat.length - 1; s++) {
            long r0 = (long) Math.floor(Math.min(lat[s], lat[s + 1]) / CELL_DEGREES);
            long r1 = (long) Math.floor(Math.max(lat[s], lat[s + 1]) / CELL_DEGREES);
            long c0 = (long) Math.floor(Math.min(lon[s], lon[s + 1]) / CELL_DEGREES);
            long c1 = (long) Math.floor(Math.max(lon[s], lon[s + 1]) / CELL_DEGREES);
            for (long r = r0; r <= r1; r++) {
                for (long c = c0; c <= c1; c++) {
                    long key = key(r, c);
                    int[] list = cells.get(key);
                    int size = sizes.getOrDefault(key, 0);
                    if (list == null) {
                        list = new int[4];
                    } else if (size == list.length) {
                        list = Arrays.copyOf(list, size * 2);
                    }
                    list[size] = s;
                    cells.put(key, list);
                    sizes.put(key, size + 1);
                }
            }
        }
        Map<Long, int[]> grid = new HashMap<>(cells.size() * 2);
        cells.forEach((key, list) -> grid.put(key, Arrays.copyOf(list, sizes.get(key))));
        return grid;
    }

    private static long key(long row, long col) {
        return (row << 32) | (col & 0xffffffffL);
    }
}
