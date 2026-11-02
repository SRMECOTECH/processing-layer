package com.srm.gps.processing.geofence;

import java.util.Map;

/**
 * How sure the tracker must be before it believes a vehicle crossed a fence border.
 *
 * The confirmation and escape rules are the smart_truck ones (backend/app/services/geofence/detection.py), so the live
 * events agree with the enter/exit rows smart_truck already holds in tta_trip_geofence_events:
 *
 * @param confirmSeconds          a new side of the border is believed once the vehicle has stayed on it this long
 *                                (15 min): GPS scatter at a standstill is tens to hundreds of metres, and a truck parked
 *                                on the border otherwise reads in/out/in/out
 * @param escapeFactor            ... or at once, for an exit, when a reading is more than this many radii from the
 *                                centre (2): a truck 6 km out of a 3 km fence is not scatter, even if its GPS then dies
 * @param passMinTravelMeters     a short stay inside that never held is reported as a pass-through when the vehicle
 *                                moved at least this far while doing it (otherwise it was scatter at the border)
 * @param crossingMaxGapSeconds   a fence is inferred as crossed between two readings with nothing inside only when they
 *                                are at most this far apart (30 min): over a longer gap the truck did not drive straight
 * @param crossingMinMoveMeters   ... and at least this far apart (a crawl cannot jump over a fence)
 * @param defaultDwellAlertMinutes  inside longer than this raises GEOFENCE_DWELL, unless the fence or its role says else;
 *                                  0 switches dwell alerts off
 * @param dwellAlertMinutesByRole   per role (ORIGIN, DESTINATION, YARD ...): smart_truck's own visits put the 90th
 *                                  percentile at about 10 h at destinations and 11 h at the origin plant
 */
public record GeofenceSettings(
        long confirmSeconds,
        double escapeFactor,
        double passMinTravelMeters,
        long crossingMaxGapSeconds,
        double crossingMinMoveMeters,
        int defaultDwellAlertMinutes,
        Map<String, Integer> dwellAlertMinutesByRole) {

    public GeofenceSettings {
        dwellAlertMinutesByRole = dwellAlertMinutesByRole == null ? Map.of() : Map.copyOf(dwellAlertMinutesByRole);
    }

    public static GeofenceSettings defaults() {
        return new GeofenceSettings(15 * 60, 2.0, 300, 30 * 60, 200, 360,
                Map.of("ORIGIN", 720, "DESTINATION", 360, "YARD", 240));
    }

    /** The dwell limit of this fence in minutes; 0 or less means no dwell alert. */
    public int dwellAlertMinutes(Geofence fence) {
        if (fence.dwellAlertMinutes() != null) {
            return fence.dwellAlertMinutes();
        }
        return dwellAlertMinutesByRole.getOrDefault(fence.role(), defaultDwellAlertMinutes);
    }
}
