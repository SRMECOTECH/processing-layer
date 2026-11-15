package com.srm.gps.processing.road;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoadSpeedTest {

    private static final double M = 1 / 111_320.0;   // one metre north, in degrees

    /** A straight east-west road at latitude 23.0 from longitude 86.0, lengthKm long. */
    private static RoadSegment road(long id, String ref, String roadClass, int density, int lanes, Integer osm, Integer manual,
                                    double fromLon, double lengthKm) {
        double toLon = fromLon + lengthKm * 1000 / (111_320.0 * Math.cos(Math.toRadians(23)));
        List<double[]> line = List.of(new double[]{23.0, fromLon}, new double[]{23.0, toLon});
        return new RoadSegment(id, "k" + id, id, null, ref, roadClass, density, lanes, "JH", osm, manual, lengthKm * 1000, line);
    }

    @Test
    void theLimitComesFromTheHandSetValueThenOpenStreetMapThenTheTable() {
        RoadSpeedTable table = RoadSpeedTable.defaults();
        assertEquals(new RoadSpeedTable.Limit(30, RoadSpeedTable.Source.MANUAL, RoadSpeedTable.Area.OPEN),
                table.limitFor(road(1, "NH19", "trunk", 2, 1, 70, 30, 86, 1)));
        assertEquals(new RoadSpeedTable.Limit(40, RoadSpeedTable.Source.OSM, RoadSpeedTable.Area.OPEN),
                table.limitFor(road(1, "NH19", "trunk", 2, 1, 40, null, 86, 1)), "OpenStreetMap stricter than the table");
        assertEquals(new RoadSpeedTable.Limit(80, RoadSpeedTable.Source.TABLE, RoadSpeedTable.Area.OPEN),
                table.limitFor(road(1, null, "motorway", 2, 2, 100, null, 86, 1)), "a car limit of 100 does not raise a truck's 80");
        assertEquals(60, table.limitFor(road(1, "NH19", "trunk", 2, 1, null, null, 86, 1)).kmph(), "two-lane NH, open road");
        assertEquals(80, table.limitFor(road(1, "NH19", "trunk", 2, 2, null, null, 86, 1)).kmph(), "divided 4-lane NH");
        assertEquals(50, table.limitFor(road(1, "NH19", "trunk", 11, 1, null, null, 86, 1)).kmph(), "NH through a town");
        assertNull(table.limitFor(road(1, null, "path", 2, 1, null, null, 86, 1)), "a class the table does not know");
    }

    @Test
    void aStatesOwnRowBeatsTheAllIndiaRow() {
        List<RoadSpeedTable.Row> rows = new ArrayList<>(RoadSpeedTable.defaults().rows());
        rows.add(new RoadSpeedTable.Row("trunk", RoadSpeedTable.Area.OPEN, "JH", 55, null));
        RoadSpeedTable table = new RoadSpeedTable(rows, 9);
        assertEquals(55, table.limitFor(road(1, "NH19", "trunk", 2, 2, null, null, 86, 1)).kmph());
    }

    @Test
    void roadNumbersAndNamesAreToldApart() {
        assertEquals("NH19", RoadSegment.refOf(List.of("Grand Trunk Road", "NH 19")));
        assertEquals("SH4", RoadSegment.refOf(List.of("SH-4")));
        assertEquals("Grand Trunk Road", RoadSegment.nameOf(List.of("NH19", "Grand Trunk Road")));
        assertNull(RoadSegment.refOf(List.of("Tata Road")));
        assertEquals(RoadSegment.keyOf(5, new double[]{1, 2}, new double[]{3, 4}), RoadSegment.keyOf(5, new double[]{3, 4}, new double[]{1, 2}),
                "both directions of a road are one piece");
    }

    @Test
    void theIndexFindsTheNearestRoadWithinTheMatchDistanceOnly() {
        RoadSegment highway = road(1, "NH19", "trunk", 2, 2, null, null, 86.0, 3);
        RoadSegment parallel = new RoadSegment(2, "k2", 2, "Service Road", null, "service_other", 2, 1, "JH", null, null, 3000,
                List.of(new double[]{23.0 + 25 * M, 86.0}, new double[]{23.0 + 25 * M, 86.03}));
        RoadIndex index = new RoadIndex(List.of(highway, parallel));
        assertEquals(1, index.locate(23.0 + 5 * M, 86.01, 35).road().id());
        assertEquals(2, index.locate(23.0 + 20 * M, 86.01, 35).road().id());
        assertNull(index.locate(23.0 - 60 * M, 86.01, 35), "60 m from every road");
        assertNotNull(index.locate(23.0, 86.0249, 35), "a point near the end of a long piece, in another grid cell");
    }

    @Test
    void theDayIsSplitByRoadWithDistanceAndTimeAboveEachRoadsLimit() {
        RoadSegment town = road(1, "NH19", "trunk", 12, 1, null, null, 86.0, 2);      // 50 km/h in town
        RoadSegment open = road(2, "NH19", "trunk", 2, 1, null, null, 86.0196, 5);    // 60 km/h open road
        RoadIndex index = new RoadIndex(List.of(town, open));
        List<RoadDayTotals.Reading> readings = new ArrayList<>();
        Instant t = Instant.parse("2026-07-13T06:00:00Z");
        double[] lons = {86.002, 86.006, 86.010, 86.030, 86.040};
        int[] speeds = {45, 55, 58, 70, 50};
        for (int i = 0; i < lons.length; i++) {
            readings.add(new RoadDayTotals.Reading(t.plusSeconds(60L * i), 23.0, lons[i], speeds[i], 800, 60));
        }
        readings.add(new RoadDayTotals.Reading(t.plusSeconds(600), 23.2, 86.5, 30, 500, 60));   // off every road
        List<RoadDayTotals.Row> rows = RoadDayTotals.of(readings, index, RoadSpeedTable.defaults(), 60, 600, 35);

        RoadDayTotals.Row inTown = rows.stream().filter(r -> r.limitKmph() == 50).findFirst().orElseThrow();
        assertEquals(3, inTown.readings());
        assertEquals(2, inTown.overReadings());
        assertEquals(1600, inTown.overDistanceM(), 0.001);
        assertEquals(120, inTown.overS());
        assertEquals(8, inTown.maxExcessKmph());
        assertEquals("TABLE", inTown.limitSource());

        RoadDayTotals.Row openRoad = rows.stream().filter(r -> r.limitKmph() == 60 && r.roadClass().equals("trunk")).findFirst().orElseThrow();
        assertEquals(1, openRoad.overReadings());
        assertEquals("COMPANY", openRoad.limitSource(), "the road allows 60, the same as the company cap");

        RoadDayTotals.Row unknown = rows.stream().filter(r -> r.label().equals(RoadDayTotals.UNKNOWN_ROAD)).findFirst().orElseThrow();
        assertEquals(1, unknown.readings());
        assertEquals(60, unknown.limitKmph());
    }
}
