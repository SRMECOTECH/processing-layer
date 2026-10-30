package com.srm.gps.processing.shipment;

import com.srm.gps.processing.shipment.ShipmentStatus.Evaluation;
import com.srm.gps.processing.shipment.ShipmentStatus.Facts;
import com.srm.gps.processing.shipment.ShipmentStatus.Stage;
import com.srm.gps.processing.shipment.ShipmentStatus.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentStatusTest {

    private static final Instant GATE_OUT = Instant.parse("2026-10-15T00:00:00Z");
    private static final Instant ETA = GATE_OUT.plusSeconds(24 * 3600);
    private static final Fence KOLKATA = new Fence(22.5726, 88.3639, 5_000);

    private static Facts facts(Instant leftOrigin, Instant arrived, Instant lastAt, Double lat, Double lon) {
        return new Facts(GATE_OUT, ETA, leftOrigin, arrived, null, null, false, lastAt, lat, lon, KOLKATA, false);
    }

    @Test
    void stagesFollowTheTrip() {
        assertEquals(Stage.BOOKED, ShipmentStatus.evaluate(facts(null, null, null, null, null), GATE_OUT.minusSeconds(60)).stage());
        assertEquals(Stage.AT_ORIGIN, ShipmentStatus.evaluate(facts(null, null, null, null, null), GATE_OUT.plusSeconds(60)).stage());
        assertEquals(Stage.IN_TRANSIT, ShipmentStatus.evaluate(facts(GATE_OUT.plusSeconds(3600), null, null, null, null), GATE_OUT.plusSeconds(7200)).stage());
        Evaluation arrived = ShipmentStatus.evaluate(facts(GATE_OUT.plusSeconds(3600), GATE_OUT.plusSeconds(8 * 3600), null, null, null), ETA.plusSeconds(3600));
        assertEquals(Stage.ARRIVED, arrived.stage());
        assertEquals(Verdict.ON_TIME, arrived.verdict());
        assertEquals(7 * 60, arrived.transitMinutes());
        assertEquals(60, arrived.originHoldMinutes());
    }

    @Test
    void aTruckFarFromTheDestinationJustBeforeTheEtaIsAtRisk() {
        // about 240 km from Kolkata at 40 km/h, with 2 hours to the ETA
        Instant now = ETA.minusSeconds(2 * 3600);
        Evaluation e = ShipmentStatus.evaluate(facts(GATE_OUT.plusSeconds(3600), null, now, 22.80, 86.20), now);
        assertEquals(Verdict.AT_RISK, e.verdict());
        assertNotNull(e.estimatedArrivalAt());
        assertTrue(e.remainingKm() > 200);
    }

    @Test
    void stillOnTheRoadAfterTheEtaIsDelayedAndSilentWithoutRecentGps() {
        Instant now = ETA.plusSeconds(3 * 3600);
        Evaluation e = ShipmentStatus.evaluate(facts(GATE_OUT.plusSeconds(3600), null, ETA, 22.80, 86.20), now);
        assertEquals(Verdict.DELAYED, e.verdict());
        assertEquals(180, e.delayMinutes());
        assertTrue(e.gpsSilent());
    }

    @Test
    void anArrivalReportedByTheTmsCountsWhenTheGpsSawNone() {
        Facts f = new Facts(GATE_OUT, ETA, null, null, ETA.plusSeconds(1800), null, true, null, null, null, KOLKATA, false);
        Evaluation e = ShipmentStatus.evaluate(f, ETA.plusSeconds(7200));
        assertEquals(Stage.ARRIVED, e.stage());
        assertEquals(Verdict.LATE, e.verdict());
        assertEquals(30, e.delayMinutes());
        assertFalse(e.gpsSilent());
    }

    @Test
    void closedByTheNextUploadWithoutAnArrival() {
        Facts f = new Facts(GATE_OUT, ETA, null, null, null, GATE_OUT.plusSeconds(3600), false, null, null, null, KOLKATA, false);
        assertEquals(Stage.CLOSED, ShipmentStatus.evaluate(f, ETA).stage());
    }
}
