package com.srm.gps.processing.rule;

import com.srm.gps.configuration.message.MovementStatus;

/**
 * Decides whether a vehicle is moving.
 * A parked truck's GPS still "wanders" a few metres, so small jumps are ignored.
 */
public final class MovementRules {

    /** At or above this reported speed the vehicle is moving. */
    public static final int MOVING_SPEED_THRESHOLD_KMPH = 5;

    /** A jump at least this long since the previous reading also counts as moving, even if the device reports speed 0. */
    public static final double MOVING_DISTANCE_THRESHOLD_METERS = 50;

    private MovementRules() {
    }

    public static MovementStatus decideMovementStatus(int speedKmph, double distanceFromPreviousMeters) {
        if (speedKmph >= MOVING_SPEED_THRESHOLD_KMPH || distanceFromPreviousMeters >= MOVING_DISTANCE_THRESHOLD_METERS) {
            return MovementStatus.MOVING;
        }
        return MovementStatus.STOPPED;
    }
}
