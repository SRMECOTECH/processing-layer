package com.srm.gps.application.trip;

import com.srm.gps.processing.trip.TripDetectionSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Thresholds of the trip detection (properties starting with "gps.trips"), Traccar's defaults unless overridden.
 * Today one setting for the whole platform; per vehicle / per tenant overrides come later.
 *
 * @param minDistanceMeters  a trip starts once the vehicle is this far from where it stopped
 * @param minDurationSeconds a stop is declared after this long without moving minDistance
 * @param stopGapSeconds     silence longer than this ends the trip at the last reading before it
 */
@ConfigurationProperties(prefix = "gps.trips")
public record TripDetectionProperties(
        @DefaultValue("200") double minDistanceMeters,
        @DefaultValue("180") long minDurationSeconds,
        @DefaultValue("3600") long stopGapSeconds) {

    public TripDetectionSettings toSettings() {
        return new TripDetectionSettings(minDistanceMeters, minDurationSeconds, stopGapSeconds);
    }
}
