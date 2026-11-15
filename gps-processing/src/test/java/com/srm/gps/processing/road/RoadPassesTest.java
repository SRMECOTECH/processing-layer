package com.srm.gps.processing.road;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoadPassesTest {

    private static final Instant T0 = Instant.parse("2026-11-08T13:00:00Z");

    private static RoadSegment road(long id, String ref, String roadClass, double lengthM) {
        return new RoadSegment(id, "k" + id, id, null, ref, roadClass, 2, 1, null, null, null, lengthM,
                List.of(new double[]{23.0, 86.0}, new double[]{23.0, 86.01}));
    }

    /** One reading a minute at the given speed, 1 km apart when moving. */
    private static RoadPasses.Reading at(int minute, int speed, Long road) {
        return new RoadPasses.Reading(T0.plusSeconds(minute * 60L), 23.0, 86.0 + minute * 0.01, speed, speed > 0 ? 1000 : 0,
                minute == 0 ? 0 : 60, road);
    }

    private static final Map<Long, RoadPasses.Piece> PIECES = Map.of(
            1L, new RoadPasses.Piece(road(1, "NH19", "trunk", 1000), 60, false, 2),
            2L, new RoadPasses.Piece(road(2, "NH19", "trunk", 1000), 40, true, 5),
            3L, new RoadPasses.Piece(road(3, null, "tertiary", 500), 30, false, 0));

    @Test
    void consecutiveReadingsOnOneRoadAreOnePassAndAJunctionBlipStaysInIt() {
        List<RoadPasses.Reading> drive = new ArrayList<>();
        drive.add(at(0, 50, 1L));
        drive.add(at(1, 55, 1L));
        drive.add(at(2, 50, 3L));     // one reading snapped to the side road: a junction
        drive.add(at(3, 70, 2L));
        drive.add(at(4, 0, 2L));
        drive.add(at(5, 30, 3L));     // two readings: really on the tertiary road
        drive.add(at(6, 30, 3L));

        List<RoadPasses.Pass> passes = RoadPasses.of(drive, PIECES, RoadSpeedTable.defaults(), 60, 600);

        assertEquals(2, passes.size());
        RoadPasses.Pass nh = passes.getFirst();
        assertEquals("NH19", nh.label());
        assertEquals(5, nh.readings());
        assertEquals(2, nh.pieces(), "the side road's piece is not one of the NH's");
        assertEquals(60, nh.limitKmph());
        assertEquals(1, nh.overReadings(), "70 on a 60 road");
        assertEquals(10, nh.maxExcessKmph());
        assertEquals(1, nh.bridges());
        assertEquals(5, nh.maxCurvature());
        assertEquals(180, nh.movingS());
        assertEquals(60, nh.stoppedS());
        assertEquals(48.0, nh.expectedSpeedKmph(), 0.01, "1 km at 60 + 1 km at 40 = 2 km in 2.5 min");
        assertEquals(1L, nh.firstSegmentId());
        assertEquals(2L, nh.lastSegmentId());

        RoadPasses.Pass side = passes.get(1);
        assertEquals("tertiary", side.roadClass());
        assertEquals(2, side.readings());
        assertEquals(50, side.limitKmph(), "the table's tertiary open-road limit, below the company's 60");
        assertEquals("TABLE", side.limitSource());
    }

    @Test
    void readingsOnNoKnownRoadAreTheirOwnPassJudgedByTheCompanyLimit() {
        List<RoadPasses.Reading> drive = List.of(at(0, 20, null), at(1, 20, 99L), at(2, 40, 1L), at(3, 40, 1L));

        List<RoadPasses.Pass> passes = RoadPasses.of(drive, PIECES, RoadSpeedTable.defaults(), 60, 600);

        assertEquals(2, passes.size());
        assertEquals(RoadDayTotals.UNKNOWN_ROAD, passes.getFirst().label());
        assertEquals("COMPANY", passes.getFirst().limitSource());
        assertNull(passes.getFirst().roadLimitKmph());
        assertNull(passes.getFirst().expectedSpeedKmph());
        assertEquals(2, passes.getFirst().readings(), "an id the library no longer has counts as unknown");
    }
}
