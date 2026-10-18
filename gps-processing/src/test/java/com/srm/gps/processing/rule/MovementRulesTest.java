package com.srm.gps.processing.rule;

import com.srm.gps.configuration.message.MovementStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementRulesTest {

    @Test
    void distanceBetweenTwoKnownPointsIsCorrect() {
        // Jamshedpur to Kolkata is roughly 230 km in a straight line.
        double meters = GeoDistanceCalculator.distanceInMeters(22.8046, 86.2029, 22.5726, 88.3639);

        assertEquals(223_000, meters, 5_000);
    }

    @Test
    void aVehicleReportingSpeedIsMoving() {
        assertEquals(MovementStatus.MOVING, MovementRules.decideMovementStatus(30, 0));
    }

    @Test
    void smallGpsWanderWhileParkedIsStopped() {
        assertEquals(MovementStatus.STOPPED, MovementRules.decideMovementStatus(0, 4.2));
    }

    @Test
    void aLargeJumpCountsAsMovingEvenWithZeroSpeed() {
        assertEquals(MovementStatus.MOVING, MovementRules.decideMovementStatus(0, 120));
    }
}
