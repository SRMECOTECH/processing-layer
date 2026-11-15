package com.srm.gps.processing.shipment;

import com.srm.gps.processing.rule.GeoDistanceCalculator;

/** A circular geofence: a plant, a city, a consignee's yard. */
public record Fence(double latitude, double longitude, double radiusMeters) {

    public boolean contains(double lat, double lon) {
        return distanceMeters(lat, lon) <= radiusMeters;
    }

    public double distanceMeters(double lat, double lon) {
        return GeoDistanceCalculator.distanceInMeters(latitude, longitude, lat, lon);
    }

    /** True when the other fence's centre lies inside this one: a trip between them never leaves this fence. */
    public boolean containsCentreOf(Fence other) {
        return other != null && contains(other.latitude, other.longitude);
    }
}
