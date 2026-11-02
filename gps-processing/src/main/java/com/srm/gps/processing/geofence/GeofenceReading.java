package com.srm.gps.processing.geofence;

import java.time.Instant;

/**
 * What the geofence tracker needs from one processed reading.
 *
 * @param secondsSincePrevious  seconds since the device's previous reading, as the processing step counted it (0 for
 *                              the first): tells the tracker when the previous reading was taken even when it does not
 *                              remember that reading itself
 * @param arrivedOutOfOrder     older than a reading already processed for the device: never moves the live state back
 */
public record GeofenceReading(Instant recordedAt, double latitude, double longitude, int speedKmph,
                              long secondsSincePrevious, boolean arrivedOutOfOrder) {
}
