package com.srm.gps.connectivity.smarttruck;

import java.time.Instant;

/**
 * One row of smart_truck.tta_trip_gps, with readable names.
 *
 * @param tripNumber           i_trip_no
 * @param vehicleRegistration  s_asset_id      (for example XX01AB1234)
 * @param deviceImei           s_device_id
 * @param transporterName      s_entity_name   (for example DEMO-TRANSPORT-JSR)
 * @param recordedAt           dt_message, converted from India time to an Instant
 * @param latitude             d_lat
 * @param longitude            d_long
 * @param speedKmph            i_speed
 */
public record SmartTruckGpsPing(
        long tripNumber,
        String vehicleRegistration,
        String deviceImei,
        String transporterName,
        Instant recordedAt,
        double latitude,
        double longitude,
        int speedKmph) {
}
