package com.srm.gps.processing.road;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One vehicle's day, road by road: how far and how long it drove on each road, that road's limit, how fast it went
 * and how much of it was above the limit. The leg ending at a reading (distance and time since the previous one) is
 * counted on the road under that reading.
 *
 * "Above the limit" here is every leg whose reading was faster than the limit, without the over-speed rule's minimum
 * duration: it shows exposure (km and minutes), next to the confirmed over-speed episodes the speed module raised.
 *
 * Readings on no stored road (not map-matched yet, or off-road yards) are counted under one "not on a known road" row
 * judged by the company limit.
 */
public final class RoadDayTotals {

    /** One reading of the day, oldest first (gps_position). */
    public record Reading(Instant recordedAt, double latitude, double longitude, int speedKmph, double distanceFromPreviousM,
                          long secondsSincePrevious) {
    }

    /**
     * One road of the day.
     *
     * @param roadKey      the group: road label + class + limit (a road whose limit changes is two rows)
     * @param limitSource  MANUAL, OSM, TABLE, or COMPANY (not on a known road, or the road's limit is above the company's)
     */
    public record Row(String roadKey, String label, String ref, String roadClass, String area, String stateCode, int limitKmph,
                      String limitSource, int readings, double distanceM, long movingS, int maxSpeedKmph, double movingSpeedSum,
                      int movingReadings, int overReadings, double overDistanceM, long overS, int maxExcessKmph) {

        public double averageMovingSpeedKmph() {
            return movingReadings == 0 ? 0 : movingSpeedSum / movingReadings;
        }
    }

    public static final String UNKNOWN_ROAD = "(not on a known road)";

    private RoadDayTotals() {
    }

    /**
     * @param companyLimitKmph the vehicle's own cap (its speed rule): a road's limit counts only where it is lower
     * @param maxGapSeconds    a longer gap is not counted as driving time (the truck was out of contact)
     * @param maxMatchMeters   how far from a road's line a reading may be and still be on it
     */
    public static List<Row> of(List<Reading> readings, RoadIndex index, RoadSpeedTable table, int companyLimitKmph,
                               long maxGapSeconds, double maxMatchMeters) {
        Map<String, Acc> rows = new LinkedHashMap<>();
        for (Reading r : readings) {
            RoadIndex.Match match = index.locate(r.latitude(), r.longitude(), maxMatchMeters);
            RoadSegment road = match == null ? null : match.road();
            RoadSpeedTable.Limit roadLimit = road == null ? null : table.limitFor(road);
            int limit = companyLimitKmph;
            String source = "COMPANY";
            if (roadLimit != null && roadLimit.kmph() < companyLimitKmph) {
                limit = roadLimit.kmph();
                source = roadLimit.source().name();
            }
            String label = road == null ? UNKNOWN_ROAD : road.label();
            String roadClass = road == null ? "" : road.roadClass();
            String key = label + "|" + roadClass + "|" + limit;
            final int rowLimit = limit;
            final String rowSource = source;
            Acc acc = rows.computeIfAbsent(key, k -> new Acc(k, label, road == null ? null : road.ref(), roadClass,
                    road == null ? "" : table.areaOf(road).name(), road == null ? null : road.stateCode(), rowLimit, rowSource));
            acc.add(r, maxGapSeconds);
        }
        List<Row> result = new ArrayList<>();
        for (Acc acc : rows.values()) {
            result.add(acc.row());
        }
        result.sort(Comparator.comparingDouble(Row::distanceM).reversed());
        return result;
    }

    private static final class Acc {
        final String key;
        final String label;
        final String ref;
        final String roadClass;
        final String area;
        final String stateCode;
        final int limit;
        final String source;
        int readings;
        double distance;
        long moving;
        int maxSpeed;
        double speedSum;
        int movingReadings;
        int overReadings;
        double overDistance;
        long overSeconds;
        int maxExcess;

        Acc(String key, String label, String ref, String roadClass, String area, String stateCode, int limit, String source) {
            this.key = key;
            this.label = label;
            this.ref = ref;
            this.roadClass = roadClass;
            this.area = area;
            this.stateCode = stateCode;
            this.limit = limit;
            this.source = source;
        }

        void add(Reading r, long maxGapSeconds) {
            readings++;
            boolean counted = r.secondsSincePrevious() > 0 && r.secondsSincePrevious() <= maxGapSeconds;
            if (counted) {
                distance += r.distanceFromPreviousM();
            }
            maxSpeed = Math.max(maxSpeed, r.speedKmph());
            if (r.speedKmph() > 0) {
                movingReadings++;
                speedSum += r.speedKmph();
                if (counted) {
                    moving += r.secondsSincePrevious();
                }
            }
            if (r.speedKmph() > limit) {
                overReadings++;
                maxExcess = Math.max(maxExcess, r.speedKmph() - limit);
                if (counted) {
                    overDistance += r.distanceFromPreviousM();
                    overSeconds += r.secondsSincePrevious();
                }
            }
        }

        Row row() {
            return new Row(key, label, ref, roadClass, area, stateCode, limit, source, readings, distance, moving, maxSpeed,
                    speedSum, movingReadings, overReadings, overDistance, overSeconds, maxExcess);
        }
    }
}
