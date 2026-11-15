package com.srm.gps.processing.shipment;

import com.srm.gps.processing.shipment.ShipmentProgress.Reading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The real places of trip 754710: Tata Steel Jamshedpur (10 km origin fence) to Adityapur, and a Kolkata trip. */
class ShipmentProgressTest {

    private static final Fence JAMSHEDPUR_PLANT = new Fence(22.788598, 86.199600, 10_000);
    private static final Fence ADITYAPUR = new Fence(22.796308, 86.137673, 1_153);
    private static final Fence KOLKATA = new Fence(22.5726, 88.3639, 5_000);
    private static final Instant GATE_OUT = Instant.parse("2026-08-03T05:50:43Z");

    private static Reading at(long minutesAfterGateOut, double lat, double lon, int speed) {
        return new Reading(GATE_OUT.plusSeconds(minutesAfterGateOut * 60), lat, lon, speed, speed >= 5);
    }

    private static ShipmentProgress run(Fence origin, Fence destination, List<Reading> readings) {
        ShipmentProgress p = ShipmentProgress.NONE;
        for (Reading r : readings) {
            p = ShipmentProgress.apply(p, GATE_OUT, origin, destination, r);
        }
        return p;
    }

    /** Plant -> outside the 10 km fence (towards Kolkata) -> Kolkata. */
    private static List<Reading> kolkataTrip() {
        return List.of(
                at(-30, 22.7886, 86.1996, 0),     // still in the plant before the gate-out: not the trip
                at(5, 22.7886, 86.1996, 0),       // past the gate-out, waiting inside the plant fence
                at(40, 22.7700, 86.2800, 35),     // about 8.4 km out: still inside the 10 km fence
                at(60, 22.7500, 86.3500, 45),     // about 16 km out: departed
                at(400, 22.6000, 88.3000, 40),
                at(420, 22.5730, 88.3640, 10));   // inside Kolkata
    }

    @Test
    void departureIsTheFirstReadingOutsideTheOriginFenceAndArrivalTheFirstInsideTheDestination() {
        ShipmentProgress p = run(JAMSHEDPUR_PLANT, KOLKATA, kolkataTrip());
        assertEquals(GATE_OUT.plusSeconds(60 * 60), p.leftOriginAt());
        assertEquals(GATE_OUT.plusSeconds(420 * 60), p.arrivedAt());
        assertEquals(GATE_OUT.plusSeconds(420 * 60), p.lastReadingAt());
    }

    @Test
    void theSameReadingsInAnyOrderOrTwiceGiveTheSameResult() {
        ShipmentProgress inOrder = run(JAMSHEDPUR_PLANT, KOLKATA, kolkataTrip());
        List<Reading> shuffledAndDoubled = new ArrayList<>(kolkataTrip());
        shuffledAndDoubled.addAll(kolkataTrip());
        Collections.reverse(shuffledAndDoubled);
        ShipmentProgress replayed = run(JAMSHEDPUR_PLANT, KOLKATA, shuffledAndDoubled);
        assertEquals(inOrder.leftOriginAt(), replayed.leftOriginAt());
        assertEquals(inOrder.lastReadingAt(), replayed.lastReadingAt());
        assertEquals(inOrder.lastLatitude(), replayed.lastLatitude());
    }

    @Test
    void aLateReadingFromTheDeviceMemoryMovesTheDepartureEarlier() {
        ShipmentProgress live = run(JAMSHEDPUR_PLANT, KOLKATA, List.of(at(5, 22.7886, 86.1996, 0), at(90, 22.7400, 86.4000, 45)));
        assertEquals(GATE_OUT.plusSeconds(90 * 60), live.leftOriginAt());
        // the device was out of coverage at minute 60 and sends that reading when it is back
        ShipmentProgress late = ShipmentProgress.apply(live, GATE_OUT, JAMSHEDPUR_PLANT, KOLKATA, at(60, 22.7500, 86.3500, 45));
        assertEquals(GATE_OUT.plusSeconds(60 * 60), late.leftOriginAt());
        assertEquals(GATE_OUT.plusSeconds(90 * 60), late.lastReadingAt());   // the late reading is not "now"
    }

    @Test
    void anIntraFenceTripDepartsWhenItMovesOffBecauseItNeverLeavesTheOriginFence() {
        // Adityapur lies inside the plant's 10 km fence, like trip 754710 (s_geofence_out_status = intra_fence)
        ShipmentProgress p = run(JAMSHEDPUR_PLANT, ADITYAPUR, List.of(
                at(5, 22.7886, 86.1996, 0),
                at(20, 22.7900, 86.1800, 30),
                at(45, 22.7963, 86.1377, 5)));
        assertEquals(GATE_OUT.plusSeconds(20 * 60), p.leftOriginAt());
        assertEquals(GATE_OUT.plusSeconds(45 * 60), p.arrivedAt());
    }

    @Test
    void aTripThatStartsInsideItsDestinationFenceArrivesOnlyAfterLeavingIt() {
        Fence jamshedpurCity = new Fence(22.773607, 86.216815, 1_000);
        ShipmentProgress p = run(null, jamshedpurCity, List.of(at(1, 22.7736, 86.2168, 0)));
        assertNull(p.arrivedAt());
        p = ShipmentProgress.apply(p, GATE_OUT, null, jamshedpurCity, at(15, 22.7600, 86.2400, 30));
        p = ShipmentProgress.apply(p, GATE_OUT, null, jamshedpurCity, at(30, 22.7737, 86.2169, 3));
        assertEquals(GATE_OUT.plusSeconds(30 * 60), p.arrivedAt());
    }

    @Test
    void readingsBeforeTheGateOutAreIgnored() {
        ShipmentProgress before = ShipmentProgress.NONE;
        assertSame(before, ShipmentProgress.apply(before, GATE_OUT, JAMSHEDPUR_PLANT, KOLKATA, at(-10, 22.60, 88.30, 40)));
    }
}
