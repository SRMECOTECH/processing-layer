package com.srm.gps.processing.road;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A vehicle's drive cut into road passes: consecutive readings on one road (label + class + the limit they were judged
 * by, the day report's road key), from the first reading on it to the last. A single reading on another road between two
 * of the same road (crossing a junction, a point snapped to a slip road) stays in the pass around it.
 *
 * The leg ending at a reading (distance and time since the previous one) counts on that reading's pass; a gap longer
 * than maxGapSeconds (out of contact) counts as neither driving nor standing.
 */
public final class RoadPasses {

    /** One stored reading, oldest first, with the road piece it was on (null: none within the match distance). */
    public record Reading(Instant recordedAt, double latitude, double longitude, int speedKmph, double distanceFromPreviousM,
                          long secondsSincePrevious, Long roadId) {
    }

    /** A road piece with what the pass adds up from it. */
    public record Piece(RoadSegment road, Integer expectedSpeedKmph, boolean bridge, Integer curvature) {
    }

    /**
     * @param roadLimitKmph     the road's own limit (null: no known road, or the table has no row for its class)
     * @param limitKmph         the limit the readings were judged by: the lower of the road's and the vehicle's own
     * @param expectedSpeedKmph Valhalla's routing speed over the pass's pieces, length-weighted (null: none known)
     */
    public record Pass(String roadKey, String label, String ref, String roadClass, String area, Integer roadLimitKmph,
                       int limitKmph, String limitSource, Instant enteredAt, Instant leftAt, int readings, int pieces,
                       double distanceM, long movingS, long stoppedS, double avgMovingSpeedKmph, int maxSpeedKmph,
                       Double expectedSpeedKmph, int overReadings, long overS, int maxExcessKmph, int bridges,
                       Integer maxCurvature, Long firstSegmentId, Long lastSegmentId, double entryLatitude,
                       double entryLongitude, double exitLatitude, double exitLongitude) {
    }

    private RoadPasses() {
    }

    private record Judged(Reading reading, Piece piece, String key, String label, String ref, String roadClass, String area,
                          Integer roadLimit, int limit, String source) {
    }

    public static List<Pass> of(List<Reading> readings, Map<Long, Piece> pieces, RoadSpeedTable table, int companyLimitKmph,
                                long maxGapSeconds) {
        List<Judged> judged = new ArrayList<>(readings.size());
        for (Reading r : readings) {
            Piece piece = r.roadId() == null ? null : pieces.get(r.roadId());
            RoadSegment road = piece == null ? null : piece.road();
            RoadSpeedTable.Limit roadLimit = road == null ? null : table.limitFor(road);
            int limit = companyLimitKmph;
            String source = "COMPANY";
            if (roadLimit != null && roadLimit.kmph() < companyLimitKmph) {
                limit = roadLimit.kmph();
                source = roadLimit.source().name();
            }
            String label = road == null ? RoadDayTotals.UNKNOWN_ROAD : road.label();
            String roadClass = road == null ? "" : road.roadClass();
            judged.add(new Judged(r, piece, label + "|" + roadClass + "|" + limit, label, road == null ? null : road.ref(),
                    roadClass, road == null ? "" : table.areaOf(road).name(), roadLimit == null ? null : roadLimit.kmph(), limit,
                    source));
        }

        // runs of one key; a one-reading run between two runs of the same key joins them
        List<List<Judged>> runs = new ArrayList<>();
        for (Judged j : judged) {
            if (runs.isEmpty() || !runs.getLast().getFirst().key().equals(j.key())) {
                runs.add(new ArrayList<>());
            }
            runs.getLast().add(j);
        }
        List<List<Judged>> merged = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            List<Judged> run = runs.get(i);
            boolean blip = run.size() == 1 && !merged.isEmpty() && i + 1 < runs.size()
                    && merged.getLast().getFirst().key().equals(runs.get(i + 1).getFirst().key());
            if (blip) {
                merged.getLast().addAll(run);
                merged.getLast().addAll(runs.get(++i));
            } else if (!merged.isEmpty() && merged.getLast().getFirst().key().equals(run.getFirst().key())) {
                merged.getLast().addAll(run);
            } else {
                merged.add(new ArrayList<>(run));
            }
        }

        List<Pass> passes = new ArrayList<>(merged.size());
        for (List<Judged> run : merged) {
            passes.add(pass(run, maxGapSeconds));
        }
        return passes;
    }

    private static Pass pass(List<Judged> run, long maxGapSeconds) {
        Judged head = run.getFirst();
        Judged tail = run.getLast();
        double distance = 0;
        double movingDistance = 0;
        long moving = 0;
        long stopped = 0;
        long over = 0;
        int overReadings = 0;
        int maxSpeed = 0;
        int maxExcess = 0;
        Set<Long> pieceIds = new HashSet<>();
        double expectedMetres = 0;
        double expectedHours = 0;
        int bridges = 0;
        Integer maxCurvature = null;
        Long firstSegment = null;
        Long lastSegment = null;
        for (Judged j : run) {
            Reading r = j.reading();
            boolean counted = r.secondsSincePrevious() > 0 && r.secondsSincePrevious() <= maxGapSeconds;
            if (counted) {
                distance += r.distanceFromPreviousM();
                if (r.speedKmph() > 0) {
                    moving += r.secondsSincePrevious();
                    movingDistance += r.distanceFromPreviousM();
                } else {
                    stopped += r.secondsSincePrevious();
                }
            }
            maxSpeed = Math.max(maxSpeed, r.speedKmph());
            // a reading on another road inside the pass (a junction) is judged by the pass's own limit
            if (r.speedKmph() > head.limit()) {
                overReadings++;
                maxExcess = Math.max(maxExcess, r.speedKmph() - head.limit());
                if (counted) {
                    over += r.secondsSincePrevious();
                }
            }
            Piece piece = j.piece();
            if (piece != null && j.key().equals(head.key())) {
                long id = piece.road().id();
                if (firstSegment == null) {
                    firstSegment = id;
                }
                lastSegment = id;
                if (pieceIds.add(id)) {
                    if (piece.expectedSpeedKmph() != null && piece.expectedSpeedKmph() > 0) {
                        expectedMetres += piece.road().lengthMeters();
                        expectedHours += piece.road().lengthMeters() / 1000 / piece.expectedSpeedKmph();
                    }
                    if (piece.bridge()) {
                        bridges++;
                    }
                    if (piece.curvature() != null) {
                        maxCurvature = maxCurvature == null ? piece.curvature() : Math.max(maxCurvature, piece.curvature());
                    }
                }
            }
        }
        double average = moving == 0 ? 0 : movingDistance / moving * 3.6;
        Double expected = expectedHours > 0 ? expectedMetres / 1000 / expectedHours : null;
        return new Pass(head.key(), head.label(), head.ref(), head.roadClass(), head.area(), head.roadLimit(), head.limit(),
                head.source(), head.reading().recordedAt(), tail.reading().recordedAt(), run.size(), pieceIds.size(), distance,
                moving, stopped, average, maxSpeed, expected, overReadings, over, maxExcess, bridges, maxCurvature, firstSegment,
                lastSegment, head.reading().latitude(), head.reading().longitude(), tail.reading().latitude(),
                tail.reading().longitude());
    }
}
