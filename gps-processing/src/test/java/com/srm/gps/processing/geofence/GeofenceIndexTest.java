package com.srm.gps.processing.geofence;

import com.srm.gps.processing.rule.DistanceCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeofenceIndexTest {

    // smart_truck fences: Jamshedpur (10 km) holds Adityapur (1.2 km); Chennai is a 20 km town fence
    private static final Geofence JAMSHEDPUR = Geofence.circle(44, "JAMSHEDPUR", "Tata Steel Jamshedpur", "ORIGIN", 22.788598, 86.1996, 10_000, null, null);
    private static final Geofence ADITYAPUR = Geofence.circle(2, "ADITYAPUR", "ADITYAPUR", "DESTINATION", 22.796308, 86.13767333, 1_153, null, null);
    private static final Geofence CHENNAI = Geofence.circle(20, "CHENNAI", "CHENNAI", "DESTINATION", 13.0827, 80.2707, 20_000, null, null);

    @Test
    void sameAnswerAsTestingEveryFence() {
        Random random = new Random(7);
        List<Geofence> fences = new ArrayList<>(List.of(JAMSHEDPUR, ADITYAPUR, CHENNAI));
        for (int i = 0; i < 300; i++) {
            fences.add(Geofence.circle(1000 + i, "F" + i, null, "DESTINATION", 8 + random.nextDouble() * 28,
                    69 + random.nextDouble() * 27, 1_000 + random.nextInt(19_000), null, null));
        }
        GeofenceIndex index = GeofenceIndex.of(fences);
        for (int i = 0; i < 50_000; i++) {
            // half the points close to some fence, so the inside answers are tested as much as the outside ones
            Geofence near = fences.get(random.nextInt(fences.size()));
            double lat = i % 2 == 0 ? near.latitude() + (random.nextDouble() - 0.5) * 0.5 : 8 + random.nextDouble() * 28;
            double lon = i % 2 == 0 ? near.longitude() + (random.nextDouble() - 0.5) * 0.5 : 69 + random.nextDouble() * 27;
            Set<Long> brute = fences.stream().filter(f -> f.contains(lat, lon)).map(Geofence::id).collect(Collectors.toSet());
            Set<Long> indexed = index.locate(lat, lon).stream().map(Geofence::id).collect(Collectors.toSet());
            assertEquals(brute, indexed, "at " + lat + "," + lon);
        }
    }

    @Test
    void aBigFenceIsFoundInItsCornerCellsToo() {
        GeofenceIndex index = GeofenceIndex.of(List.of(CHENNAI, ADITYAPUR), GeofenceIndex.MIN_CELL_DEGREES);
        // 18 km north-east of the centre: two cells north and two east of the centre's cell (k = 1 nearest would miss it)
        double lat = CHENNAI.latitude() + DistanceCalculator.latitudeDelta(13_000);
        double lon = CHENNAI.longitude() + DistanceCalculator.longitudeDelta(13_000, CHENNAI.latitude());
        assertEquals(List.of(CHENNAI), index.locate(lat, lon));
    }

    @Test
    void nestedFencesAreBothReported() {
        GeofenceIndex index = GeofenceIndex.of(List.of(JAMSHEDPUR, ADITYAPUR, CHENNAI));
        Set<String> codes = index.locate(22.7965, 86.1380).stream().map(Geofence::code).collect(Collectors.toSet());
        assertEquals(Set.of("JAMSHEDPUR", "ADITYAPUR"), codes);
        assertTrue(index.locate(25.0, 80.0).isEmpty(), "a highway point far from every fence");
    }

    @Test
    void circleTestAgreesWithHaversineAtTheBorder() {
        // 9,980 m and 10,020 m from the centre, east and north: inside and outside
        for (double bearing : new double[]{0, 90}) {
            double inLat = JAMSHEDPUR.latitude() + (bearing == 0 ? DistanceCalculator.latitudeDelta(9_980) : 0);
            double inLon = JAMSHEDPUR.longitude() + (bearing == 90 ? DistanceCalculator.longitudeDelta(9_980, JAMSHEDPUR.latitude()) : 0);
            double outLat = JAMSHEDPUR.latitude() + (bearing == 0 ? DistanceCalculator.latitudeDelta(10_020) : 0);
            double outLon = JAMSHEDPUR.longitude() + (bearing == 90 ? DistanceCalculator.longitudeDelta(10_020, JAMSHEDPUR.latitude()) : 0);
            assertTrue(JAMSHEDPUR.contains(inLat, inLon));
            assertFalse(JAMSHEDPUR.contains(outLat, outLon));
        }
    }

    @Test
    void polygonRayCastingHandlesConcaveShapesAndCorners() {
        // a U-shaped yard: the notch in the middle is outside
        Geofence yard = Geofence.polygon(9, "YARD", null, "YARD", List.of(
                new double[]{22.00, 86.00}, new double[]{22.00, 86.03}, new double[]{22.03, 86.03}, new double[]{22.03, 86.02},
                new double[]{22.01, 86.02}, new double[]{22.01, 86.01}, new double[]{22.03, 86.01}, new double[]{22.03, 86.00}), null, null);
        assertTrue(yard.contains(22.005, 86.015), "the base of the U");
        assertTrue(yard.contains(22.02, 86.005), "the left arm");
        assertFalse(yard.contains(22.02, 86.015), "the notch");
        assertFalse(yard.contains(22.04, 86.015), "above the yard");
        // a ray through a corner's latitude (22.01) is counted once
        assertTrue(yard.contains(22.01, 86.005));
        assertFalse(yard.contains(22.01, 86.04));
        GeofenceIndex index = GeofenceIndex.of(List.of(yard, JAMSHEDPUR));
        assertEquals(List.of(yard), index.locate(22.005, 86.015));
    }

    @Test
    void movesAcrossAFenceAreFoundAlongTheirCells() {
        GeofenceIndex index = GeofenceIndex.of(List.of(ADITYAPUR, CHENNAI));
        // a straight 4 km drive west to east right through Adityapur's centre
        double lat = ADITYAPUR.latitude();
        double lon1 = ADITYAPUR.longitude() - DistanceCalculator.longitudeDelta(2_000, lat);
        double lon2 = ADITYAPUR.longitude() + DistanceCalculator.longitudeDelta(2_000, lat);
        assertTrue(index.candidatesAlong(lat, lon1, lat, lon2).contains(ADITYAPUR));
        assertEquals(0.5, ADITYAPUR.crossingFraction(lat, lon1, lat, lon2), 0.01);
        // the same drive 2 km further north misses it
        double north = lat + DistanceCalculator.latitudeDelta(2_000);
        assertTrue(ADITYAPUR.crossingFraction(north, lon1, north, lon2) < 0);
    }
}
