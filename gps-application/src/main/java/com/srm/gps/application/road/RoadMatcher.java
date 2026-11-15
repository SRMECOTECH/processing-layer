package com.srm.gps.application.road;

import com.srm.gps.application.connections.ConnectionsService;
import com.srm.gps.connectivity.http.JsonHttp;
import com.srm.gps.connectivity.valhalla.ValhallaClient;
import com.srm.gps.processing.road.RoadSegment;
import com.srm.gps.processing.route.Polyline6;
import com.srm.gps.processing.rule.DistanceCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a drive (GPS points in order) into road pieces with Valhalla's map matching: the points are thinned (a
 * standing truck adds nothing), cut into pieces Valhalla accepts (piecePoints points, pieceKm km, one point of
 * overlap), and every matched edge becomes a {@link RoadSegment}. A piece Valhalla cannot match ("no suitable edges",
 * a gap through a field) is retried as two halves once, then skipped; Valhalla not answering stops the drive.
 */
@Component
public class RoadMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RoadMatcher.class);

    /**
     * What matching one drive found; details holds each road piece's extra attributes, by its key, and points the end
     * nodes of the matched edges (junctions, toll booths ...), by their key.
     */
    public record Result(List<RoadSegment> roads, Map<String, RoadDetails> details, Map<String, RoadPoint> points, int pieces,
                         int failedPieces, int pointsSent, int matchedPoints, int unmatchedPoints, long millis, String problem) {
    }

    /** Valhalla did not answer at all: try again later. */
    public static class ValhallaUnavailable extends IOException {
        public ValhallaUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ConnectionsService connections;
    private final RoadProperties properties;
    private final ValhallaClient valhalla = new ValhallaClient(new JsonHttp(Duration.ofSeconds(90)));

    public RoadMatcher(ConnectionsService connections, RoadProperties properties) {
        this.connections = connections;
        this.properties = properties;
    }

    public String valhallaUrl() {
        return connections.valhallaUrl();
    }

    /** Valhalla's version and when its map tiles were built, for the run log. */
    public record ValhallaInfo(String url, String version, java.time.Instant tilesBuilt, java.time.Instant checkedAt) {
    }

    private volatile ValhallaInfo info;

    /** Valhalla's version and map date, asked at most every 10 minutes (null when it never answered). */
    public ValhallaInfo info() {
        ValhallaInfo current = info;
        String url = valhallaUrl();
        if (current != null && current.url().equals(url)
                && current.checkedAt().isAfter(java.time.Instant.now().minus(Duration.ofMinutes(10)))) {
            return current;
        }
        try {
            ValhallaClient.Status status = valhalla.status(url);
            info = new ValhallaInfo(url, status.version(), status.tilesBuilt(), java.time.Instant.now());
        } catch (IOException | RuntimeException unreachable) {
            LOG.debug("Valhalla status not read: {}", unreachable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return info;
    }

    /** @param points [latitude, longitude] in driving order */
    public Result match(List<double[]> points) throws ValhallaUnavailable, InterruptedException {
        List<double[]> thinned = thin(points, properties.minMoveMetersOrDefault());
        Map<String, RoadSegment> roads = new LinkedHashMap<>();
        Map<String, RoadDetails> details = new LinkedHashMap<>();
        Map<String, RoadPoint> roadPoints = new LinkedHashMap<>();
        int pieces = 0;
        int matched = 0;
        int failed = 0;
        int unmatched = 0;
        long millis = 0;
        String problem = null;
        for (List<double[]> piece : pieces(thinned, properties.piecePointsOrDefault(), properties.pieceKmOrDefault() * 1000)) {
            pieces++;
            try {
                ValhallaClient.Trace trace = trace(piece);
                millis += trace.millis();
                matched += trace.matchedPoints();
                unmatched += trace.unmatchedPoints();
                add(roads, details, roadPoints, trace);
            } catch (ValhallaUnavailable down) {
                throw down;
            } catch (IOException refused) {
                // Valhalla answered but could not match: try the two halves, each may be fine on its own
                boolean anyHalf = false;
                for (List<double[]> half : halves(piece)) {
                    try {
                        ValhallaClient.Trace trace = trace(half);
                        millis += trace.millis();
                        matched += trace.matchedPoints();
                        unmatched += trace.unmatchedPoints();
                        add(roads, details, roadPoints, trace);
                        anyHalf = true;
                    } catch (ValhallaUnavailable down) {
                        throw down;
                    } catch (IOException stillRefused) {
                        problem = stillRefused.getMessage();
                    }
                }
                if (!anyHalf) {
                    failed++;
                    problem = refused.getMessage();
                    LOG.debug("Map matching skipped a piece of {} points: {}", piece.size(), refused.getMessage());
                }
            }
        }
        return new Result(new ArrayList<>(roads.values()), details, roadPoints, pieces, failed, thinned.size(), matched, unmatched,
                millis, problem);
    }

    private ValhallaClient.Trace trace(List<double[]> piece) throws IOException, InterruptedException {
        try {
            return valhalla.trace(valhallaUrl(), piece, properties.costingOrDefault(), properties.searchRadiusOrDefault());
        } catch (ConnectException | HttpTimeoutException unreachable) {
            throw new ValhallaUnavailable("Valhalla at " + valhallaUrl() + " is not answering: " + unreachable.getMessage(), unreachable);
        } catch (IOException problem) {
            String message = String.valueOf(problem.getMessage());
            if (message.startsWith("HTTP 400") || message.startsWith("HTTP 4")) {
                throw problem;
            }
            throw new ValhallaUnavailable("Valhalla at " + valhallaUrl() + ": " + message, problem);
        }
    }

    private static void add(Map<String, RoadSegment> roads, Map<String, RoadDetails> details, Map<String, RoadPoint> points,
                            ValhallaClient.Trace trace) {
        List<double[]> shape = trace.shape().isEmpty() ? List.of() : Polyline6.decode(trace.shape());
        for (ValhallaClient.TraceEdge edge : trace.edges()) {
            int from = Math.max(0, edge.beginShapeIndex());
            int to = Math.min(shape.size() - 1, edge.endShapeIndex());
            if (to - from < 1 || edge.wayId() == 0) {
                continue;
            }
            List<double[]> line = new ArrayList<>(shape.subList(from, to + 1));
            String key = RoadSegment.keyOf(edge.wayId(), line.getFirst(), line.getLast());
            roads.putIfAbsent(key, new RoadSegment(0, key, edge.wayId(), RoadSegment.nameOf(edge.names()),
                    RoadSegment.refOf(edge.names()), edge.roadClass(), edge.density(), edge.laneCount(), edge.stateCode(),
                    edge.speedLimitKmph(), null, edge.lengthKm() * 1000, line));
            details.putIfAbsent(key, RoadDetails.of(edge.raw()));
            RoadPoint point = RoadPoint.of(edge.raw().get("end_node"), line.getLast(), key);
            if (point != null) {
                points.putIfAbsent(point.key(), point);
            }
        }
    }

    /** Leaves out points closer than minMeters to the last point kept (the last point is always kept). */
    static List<double[]> thin(List<double[]> points, double minMeters) {
        List<double[]> kept = new ArrayList<>();
        for (double[] p : points) {
            if (kept.isEmpty() || DistanceCalculator.distance(kept.getLast()[0], kept.getLast()[1], p[0], p[1]) >= minMeters) {
                kept.add(p);
            }
        }
        if (!points.isEmpty() && kept.getLast() != points.getLast()) {
            kept.add(points.getLast());
        }
        return kept;
    }

    /** Pieces of at most maxPoints points and maxMeters of line; each starts where the previous ended. */
    static List<List<double[]>> pieces(List<double[]> points, int maxPoints, double maxMeters) {
        List<List<double[]>> pieces = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        double length = 0;
        for (double[] p : points) {
            if (!current.isEmpty()) {
                double[] last = current.getLast();
                double step = DistanceCalculator.distance(last[0], last[1], p[0], p[1]);
                if (current.size() >= maxPoints || length + step > maxMeters) {
                    if (current.size() >= 2) {
                        pieces.add(current);
                    }
                    current = new ArrayList<>();
                    current.add(last);
                    length = 0;
                    step = DistanceCalculator.distance(last[0], last[1], p[0], p[1]);
                    if (step > maxMeters) {
                        // a jump longer than a whole piece (a gap in the data): start afresh at the new point
                        current.clear();
                        step = 0;
                    }
                }
                length += step;
            }
            current.add(p);
        }
        if (current.size() >= 2) {
            pieces.add(current);
        }
        return pieces;
    }

    private static List<List<double[]>> halves(List<double[]> piece) {
        if (piece.size() < 4) {
            return List.of();
        }
        int mid = piece.size() / 2;
        return List.of(new ArrayList<>(piece.subList(0, mid + 1)), new ArrayList<>(piece.subList(mid, piece.size())));
    }
}
