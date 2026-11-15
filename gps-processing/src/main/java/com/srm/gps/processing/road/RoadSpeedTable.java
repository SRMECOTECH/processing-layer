package com.srm.gps.processing.road;

import java.util.List;
import java.util.Locale;

/**
 * The speed limit of a road for goods vehicles, from the first source that has one:
 *
 *   1. MANUAL  a limit set by hand on that piece of road (a bridge, a ghat, a school stretch)
 *   2. OSM     the limit tagged in OpenStreetMap (maxspeed), when it is lower than the table's: OpenStreetMap tags the
 *              limit for cars (100 on the Agra - Lucknow Expressway), and a goods vehicle never gets more than its own
 *   3. TABLE   this table, by road class and area (TOWN when Valhalla's density is at least townDensityFrom, else
 *              OPEN road), the row of the road's state first and the all-India row ('*') otherwise. Divided roads with
 *              2+ lanes in the direction of travel use the row's multiLaneLimitKmph when it has one.
 *
 * The starting values follow the national limits for goods vehicles (80 on expressways and 4-lane divided highways, 60
 * elsewhere) and go lower in towns and on minor roads. They are starting values, meant to be checked and edited per
 * state on the console.
 *
 * @param rows             the table
 * @param townDensityFrom  Valhalla density (0..15) from which a road counts as in a town
 */
public record RoadSpeedTable(List<Row> rows, int townDensityFrom) {

    public static final String ALL_STATES = "*";

    public enum Area { TOWN, OPEN }

    public enum Source { MANUAL, OSM, TABLE }

    /** @param multiLaneLimitKmph the limit on a divided road with 2+ lanes each way, or null = same as limitKmph */
    public record Row(String roadClass, Area area, String stateCode, int limitKmph, Integer multiLaneLimitKmph) {
    }

    /** The limit of one road and where it came from. */
    public record Limit(int kmph, Source source, Area area) {
    }

    public RoadSpeedTable {
        rows = List.copyOf(rows);
    }

    public static RoadSpeedTable defaults() {
        return new RoadSpeedTable(List.of(
                new Row("motorway", Area.OPEN, ALL_STATES, 80, null),
                new Row("motorway", Area.TOWN, ALL_STATES, 80, null),
                new Row("trunk", Area.OPEN, ALL_STATES, 60, 80),
                new Row("trunk", Area.TOWN, ALL_STATES, 50, 60),
                new Row("primary", Area.OPEN, ALL_STATES, 60, 80),
                new Row("primary", Area.TOWN, ALL_STATES, 50, 60),
                new Row("secondary", Area.OPEN, ALL_STATES, 60, null),
                new Row("secondary", Area.TOWN, ALL_STATES, 40, null),
                new Row("tertiary", Area.OPEN, ALL_STATES, 50, null),
                new Row("tertiary", Area.TOWN, ALL_STATES, 40, null),
                new Row("unclassified", Area.OPEN, ALL_STATES, 40, null),
                new Row("unclassified", Area.TOWN, ALL_STATES, 30, null),
                new Row("residential", Area.OPEN, ALL_STATES, 30, null),
                new Row("residential", Area.TOWN, ALL_STATES, 30, null),
                new Row("service_other", Area.OPEN, ALL_STATES, 20, null),
                new Row("service_other", Area.TOWN, ALL_STATES, 20, null)),
                9);
    }

    public Area areaOf(RoadSegment road) {
        return road.density() >= townDensityFrom ? Area.TOWN : Area.OPEN;
    }

    /** The limit of the road, or null when no source has one (a road class missing from the table). */
    public Limit limitFor(RoadSegment road) {
        Area area = areaOf(road);
        if (road.manualLimitKmph() != null && road.manualLimitKmph() > 0) {
            return new Limit(road.manualLimitKmph(), Source.MANUAL, area);
        }
        boolean osm = road.osmLimitKmph() != null && road.osmLimitKmph() > 0;
        Row row = row(road.roadClass(), area, road.stateCode());
        if (row == null) {
            return osm ? new Limit(road.osmLimitKmph(), Source.OSM, area) : null;
        }
        boolean multiLane = road.laneCount() >= 2 && row.multiLaneLimitKmph() != null;
        int tableLimit = multiLane ? row.multiLaneLimitKmph() : row.limitKmph();
        if (osm && road.osmLimitKmph() < tableLimit) {
            return new Limit(road.osmLimitKmph(), Source.OSM, area);
        }
        return new Limit(tableLimit, Source.TABLE, area);
    }

    private Row row(String roadClass, Area area, String stateCode) {
        String state = stateCode == null ? null : stateCode.toUpperCase(Locale.ROOT);
        Row allStates = null;
        for (Row row : rows) {
            if (!row.roadClass().equalsIgnoreCase(roadClass) || row.area() != area) {
                continue;
            }
            if (state != null && state.equalsIgnoreCase(row.stateCode())) {
                return row;
            }
            if (ALL_STATES.equals(row.stateCode())) {
                allStates = row;
            }
        }
        return allStates;
    }
}
