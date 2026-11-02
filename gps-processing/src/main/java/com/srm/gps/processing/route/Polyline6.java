package com.srm.gps.processing.route;

import java.util.ArrayList;
import java.util.List;

/**
 * Google's encoded polyline format at precision 6, the way Valhalla returns route shapes: each coordinate is the
 * difference from the previous one, times 10^6, zig-zag encoded in 5-bit chunks offset by 63.
 */
public final class Polyline6 {

    private static final double FACTOR = 1e6;

    private Polyline6() {
    }

    /** The points of an encoded shape as {latitude, longitude}. */
    public static List<double[]> decode(String encoded) {
        List<double[]> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return points;
        }
        int index = 0;
        long lat = 0;
        long lon = 0;
        while (index < encoded.length()) {
            long[] next = new long[1];
            index = readValue(encoded, index, next);
            lat += next[0];
            index = readValue(encoded, index, next);
            lon += next[0];
            points.add(new double[] {lat / FACTOR, lon / FACTOR});
        }
        return points;
    }

    public static String encode(List<double[]> points) {
        StringBuilder out = new StringBuilder();
        long lastLat = 0;
        long lastLon = 0;
        for (double[] p : points) {
            long lat = Math.round(p[0] * FACTOR);
            long lon = Math.round(p[1] * FACTOR);
            writeValue(out, lat - lastLat);
            writeValue(out, lon - lastLon);
            lastLat = lat;
            lastLon = lon;
        }
        return out.toString();
    }

    private static int readValue(String encoded, int index, long[] value) {
        long result = 0;
        int shift = 0;
        int b;
        do {
            if (index >= encoded.length()) {
                throw new IllegalArgumentException("Encoded polyline ends in the middle of a value");
            }
            b = encoded.charAt(index++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        value[0] = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
        return index;
    }

    private static void writeValue(StringBuilder out, long value) {
        long v = value < 0 ? ~(value << 1) : value << 1;
        while (v >= 0x20) {
            out.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        out.append((char) (v + 63));
    }
}
