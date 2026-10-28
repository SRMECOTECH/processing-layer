package com.srm.gps.processing.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FuelEstimatorTest {

    private static final double CENT = 0.005;
    // diesel at 92, 4 km/l, 2.5 l/h idling, 60 km/h economical, 20 % penalty, 2.68 kg CO2 per litre
    private final FuelAssumptions truck = FuelAssumptions.defaults();

    @Test
    void drivingIdlingAndFastKilometresAddUp() {
        // 200 km of which 40 km above 60 km/h, 1.5 h idling
        FuelEstimate e = FuelEstimator.estimate(200, 1.5, 40, truck);

        assertEquals(50.0, e.drivingLitres(), CENT);       // 200 / 4
        assertEquals(3.75, e.idleLitres(), CENT);          // 1.5 x 2.5
        assertEquals(2.0, e.fastLitres(), CENT);           // 40 / 4 x 20 %
        assertEquals(55.75, e.usedLitres(), CENT);
        assertEquals(5.75, e.savableLitres(), CENT);       // idle + fast
        assertEquals(55.75 * 92, e.cost(), CENT);
        assertEquals(5.75 * 92, e.savableCost(), CENT);
        assertEquals(55.75 * 2.68, e.co2Kg(), CENT);
    }

    @Test
    void aVehicleThatDidNothingCostsNothing() {
        assertEquals(FuelEstimate.NONE, FuelEstimator.estimate(0, 0, 0, truck));
    }

    @Test
    void negativeInputsCountAsZero() {
        assertEquals(0, FuelEstimator.estimate(-5, -1, -2, truck).usedLitres(), CENT);
    }

    @Test
    void estimatesAddUpForAFleet() {
        FuelEstimate a = FuelEstimator.estimate(100, 1, 0, truck);
        FuelEstimate b = FuelEstimator.estimate(40, 0, 20, truck);
        assertEquals(a.usedLitres() + b.usedLitres(), a.plus(b).usedLitres(), CENT);
        assertEquals(a.savableCost() + b.savableCost(), a.plus(b).savableCost(), CENT);
    }

    @Test
    void refusesAssumptionsThatCannotBeRight() {
        assertThrows(IllegalArgumentException.class, () -> new FuelAssumptions("diesel", 92, 0, 2.5, 60, 20, 2.68));
        assertThrows(IllegalArgumentException.class, () -> new FuelAssumptions("diesel", -1, 4, 2.5, 60, 20, 2.68));
        assertThrows(IllegalArgumentException.class, () -> new FuelAssumptions("diesel", 92, 4, 2.5, 5, 20, 2.68));
        assertEquals("petrol", new FuelAssumptions("PETROL", 105, 12, 0.8, 70, 15, 2.31).fuelType());
    }
}
