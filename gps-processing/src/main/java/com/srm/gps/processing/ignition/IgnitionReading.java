package com.srm.gps.processing.ignition;

import java.time.Instant;

/** What the ignition tracker needs from one processed position. {@code ignition} is null without an ignition wire. */
public record IgnitionReading(Instant recordedAt, double latitude, double longitude, int speedKmph,
                              double distanceMeters, Boolean ignition, boolean arrivedOutOfOrder) {
}
