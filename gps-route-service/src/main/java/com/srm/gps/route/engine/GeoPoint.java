package com.srm.gps.route.engine;

import java.util.Locale;

/**
 * A point on the map in degrees (WGS-84, as GPS devices and OpenStreetMap use).
 *
 * Written as "latitude,longitude" in our API, like Google Maps shows it - for example "22.8046,86.2029" (Jamshedpur).
 * GraphHopper's POST body wants the other order, [longitude, latitude]; only {@link GraphHopperClient} deals with that.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude " + latitude + " is outside -90..90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude " + longitude + " is outside -180..180");
        }
    }

    /** Reads "latitude,longitude", for example "22.8046,86.2029". */
    public static GeoPoint parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("A point is missing: expected latitude,longitude");
        }
        String[] parts = text.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("'" + text + "' is not latitude,longitude");
        }
        try {
            return new GeoPoint(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("'" + text + "' is not latitude,longitude");
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude);
    }
}
