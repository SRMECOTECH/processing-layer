package com.srm.gps.application.road;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gps.roads.* in application.yml. Every value is optional.
 *
 * @param liveMatching      map-match the live readings in the background (default true); off = only learned trips count
 * @param matchMeters       how far from a stored road's line a reading may be and still be on it (default 35 m: GPS
 *                          scatter plus half a divided highway)
 * @param searchRadius      Valhalla's search radius around each point when map matching (default 50 m)
 * @param piecePoints       points per map-matching call (default 300; about 5 hours of driving at one ping a minute)
 * @param pieceKm           and at most this many km per call (default 80; Valhalla refuses traces over 200 km)
 * @param minMoveMeters     a point closer than this to the previous one sent is left out (a standing truck)
 * @param minLivePoints     live matching waits until a device has this many new points (default 20) ...
 * @param maxLiveWaitMinutes ... or its oldest unmatched point is this old (default 15)
 * @param costing           Valhalla costing (default truck)
 * @param maxGapSeconds     the day report counts a longer gap between readings as neither driving nor speeding (600 s)
 * @param townDensityFrom   Valhalla road density (0..15) from which a road counts as in a town (default 9)
 */
@ConfigurationProperties("gps.roads")
public record RoadProperties(Boolean liveMatching, Double matchMeters, Integer searchRadius, Integer piecePoints, Double pieceKm,
                             Double minMoveMeters, Integer minLivePoints, Integer maxLiveWaitMinutes, String costing,
                             Long maxGapSeconds, Integer townDensityFrom) {

    public boolean liveMatchingOrDefault() {
        return liveMatching == null || liveMatching;
    }

    public double matchMetersOrDefault() {
        return matchMeters != null ? matchMeters : 35;
    }

    public int searchRadiusOrDefault() {
        return searchRadius != null ? searchRadius : 50;
    }

    public int piecePointsOrDefault() {
        return piecePoints != null ? Math.max(10, piecePoints) : 300;
    }

    public double pieceKmOrDefault() {
        return pieceKm != null ? Math.min(190, Math.max(5, pieceKm)) : 80;
    }

    public double minMoveMetersOrDefault() {
        return minMoveMeters != null ? minMoveMeters : 15;
    }

    public int minLivePointsOrDefault() {
        return minLivePoints != null ? Math.max(2, minLivePoints) : 20;
    }

    public int maxLiveWaitMinutesOrDefault() {
        return maxLiveWaitMinutes != null ? maxLiveWaitMinutes : 15;
    }

    public String costingOrDefault() {
        return costing != null && !costing.isBlank() ? costing : "truck";
    }

    public long maxGapSecondsOrDefault() {
        return maxGapSeconds != null ? maxGapSeconds : 600;
    }

    public int townDensityFromOrDefault() {
        return townDensityFrom != null ? Math.max(0, Math.min(16, townDensityFrom)) : 9;
    }
}
