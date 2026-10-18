package com.srm.gps.processing.step;

import java.time.Instant;

/** The newest reading we know of one device - all that is needed to calculate movement. */
public record PreviousReading(Instant recordedAt, double latitude, double longitude) {
}
