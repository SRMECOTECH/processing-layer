package com.srm.gps.processing.geofence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Uniform grid over the map: "which fences is this reading inside?" in one hash lookup, instead of testing every
 * fence (a Cartesian product of readings x fences).
 *
 * Build: the map is cut into square cells of {@link #cellDegrees()} and every fence is filed into EVERY cell its
 * bounding box overlaps. Query: the reading's cell is two divisions; that cell's short list is tested exactly
 * ({@link Geofence#contains}). A reading on a highway lands in an empty cell and costs nothing more.
 *
 * Exact, not approximate: a point inside a fence always lies in a cell that lists the fence, so there are no false
 * negatives, and the exact test removes the false positives. Looking in one cell (not "the k nearest cells") is what
 * makes that true for a 20 km fence spread over dozens of cells as much as for a 1 km one.
 *
 * Cell size follows the MEDIAN fence diameter, never below 0.05 deg (about 5.5 km): sizing off the largest fence
 * would make every cell huge and the small fences would pile into a few long lists; the few big fences just register
 * into more cells, once, at build time. (The same choice as the smart_truck GeofenceIndex.)
 *
 * Immutable: build a new index when fences change and swap the reference.
 */
public final class GeofenceIndex {

    public static final double MIN_CELL_DEGREES = 0.05;
    /** A move longer than this many cells is a data gap, not a straight drive; no crossings are inferred from it. */
    public static final int MAX_CELLS_ALONG_A_MOVE = 2_500;

    private static final Geofence[] NONE = new Geofence[0];

    private final double cellDegrees;
    private final Map<Long, Geofence[]> cells;
    private final Map<Long, Geofence> byId;
    private final int maxPerCell;

    private GeofenceIndex(double cellDegrees, Map<Long, Geofence[]> cells, Map<Long, Geofence> byId) {
        this.cellDegrees = cellDegrees;
        this.cells = cells;
        this.byId = byId;
        this.maxPerCell = cells.values().stream().mapToInt(a -> a.length).max().orElse(0);
    }

    public static GeofenceIndex empty() {
        return of(List.of());
    }

    public static GeofenceIndex of(Collection<Geofence> fences) {
        double[] diameters = fences.stream().mapToDouble(f -> 2 * f.radiusMeters()).sorted().toArray();
        double median = diameters.length == 0 ? 0 : diameters[diameters.length / 2];
        return of(fences, Math.max(MIN_CELL_DEGREES, median / Geofence.METERS_PER_DEGREE));
    }

    public static GeofenceIndex of(Collection<Geofence> fences, double cellDegrees) {
        Map<Long, List<Geofence>> building = new HashMap<>();
        Map<Long, Geofence> byId = new LinkedHashMap<>();
        for (Geofence f : fences) {
            if (byId.putIfAbsent(f.id(), f) != null) {
                throw new IllegalArgumentException("Two geofences with id " + f.id());
            }
            int row0 = cell(f.minLatitude(), cellDegrees);
            int row1 = cell(f.maxLatitude(), cellDegrees);
            int col0 = cell(f.minLongitude(), cellDegrees);
            int col1 = cell(f.maxLongitude(), cellDegrees);
            for (int row = row0; row <= row1; row++) {
                for (int col = col0; col <= col1; col++) {
                    building.computeIfAbsent(key(row, col), k -> new ArrayList<>()).add(f);
                }
            }
        }
        Map<Long, Geofence[]> cells = new HashMap<>(building.size() * 2);
        building.forEach((k, list) -> cells.put(k, list.toArray(NONE)));
        return new GeofenceIndex(cellDegrees, cells, byId);
    }

    /** Every fence the point is inside, nested ones included (a plant inside its city). Usually empty. */
    public List<Geofence> locate(double lat, double lon) {
        Geofence[] candidates = cells.get(key(cell(lat, cellDegrees), cell(lon, cellDegrees)));
        if (candidates == null) {
            return List.of();
        }
        List<Geofence> inside = null;
        for (Geofence f : candidates) {
            if (f.contains(lat, lon)) {
                if (inside == null) {
                    inside = new ArrayList<>(2);
                }
                inside.add(f);
            }
        }
        return inside == null ? List.of() : inside;
    }

    /** The fences listed in the cells under a straight move (its bounding box): candidates for a crossing. */
    public List<Geofence> candidatesAlong(double lat1, double lon1, double lat2, double lon2) {
        int row0 = cell(Math.min(lat1, lat2), cellDegrees);
        int row1 = cell(Math.max(lat1, lat2), cellDegrees);
        int col0 = cell(Math.min(lon1, lon2), cellDegrees);
        int col1 = cell(Math.max(lon1, lon2), cellDegrees);
        if ((long) (row1 - row0 + 1) * (col1 - col0 + 1) > MAX_CELLS_ALONG_A_MOVE) {
            return List.of();
        }
        Map<Long, Geofence> found = new LinkedHashMap<>();
        for (int row = row0; row <= row1; row++) {
            for (int col = col0; col <= col1; col++) {
                Geofence[] listed = cells.get(key(row, col));
                if (listed != null) {
                    for (Geofence f : listed) {
                        found.putIfAbsent(f.id(), f);
                    }
                }
            }
        }
        return List.copyOf(found.values());
    }

    public Optional<Geofence> byId(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Geofence> fences() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    public double cellDegrees() {
        return cellDegrees;
    }

    /** Numbers for the console's "how it is calculated": fences, filled cells, cell size, longest list. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("fences", byId.size());
        stats.put("filledCells", cells.size());
        stats.put("cellDegrees", Math.round(cellDegrees * 1e6) / 1e6);
        stats.put("cellKmApprox", Math.round(cellDegrees * Geofence.METERS_PER_DEGREE / 10) / 100.0);
        stats.put("maxFencesPerCell", maxPerCell);
        stats.put("averageFencesPerFilledCell", cells.isEmpty() ? 0
                : Math.round(cells.values().stream().mapToInt(a -> a.length).average().orElse(0) * 100) / 100.0);
        return stats;
    }

    private static int cell(double degrees, double cellDegrees) {
        return (int) Math.floor(degrees / cellDegrees);
    }

    private static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    @Override
    public String toString() {
        return "GeofenceIndex" + stats();
    }
}
