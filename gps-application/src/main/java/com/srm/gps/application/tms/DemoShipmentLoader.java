package com.srm.gps.application.tms;

import com.srm.gps.application.auth.PortalUser;
import com.srm.gps.application.simulator.GpsDeviceSimulatorService;
import com.srm.gps.application.simulator.GpsDeviceSimulatorService.ReplayedTrip;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demo: the shipments behind the trips the fleet simulator is driving, so the portals show real Tata Steel trips
 * moving live. For every replayed trip its smart_truck TMS row (tta_trips) is read, its times are moved by exactly as
 * much as the simulator moved its pings, and it is imported through the same importer a transporter's Excel goes
 * through - so this is also an end-to-end test of the import.
 *
 * The rows are imported as they were when the truck left: the arrival, end and closing that the TMS recorded later
 * are left out, so the shipment-tracking module has to find them from the GPS. Loading twice changes nothing.
 */
@Service
public class DemoShipmentLoader {

    private static final DateTimeFormatter TMS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Shifted like the pings: the trip's own moments. */
    private static final Set<String> SHIFTED = Set.of("dt_booking", "dt_trip_start", "dt_trip_eta");
    /** What the TMS learned after the gate-out: left for the GPS to find. */
    private static final Set<String> LATER = Set.of("dt_trip_ata", "dt_trip_end", "s_close_reason", "dt_geofence_out");

    private final GpsDeviceSimulatorService simulator;
    private final SmartTruckGpsReader smartTruck;
    private final ShipmentImportService importer;
    private final ZoneId zone;

    public DemoShipmentLoader(GpsDeviceSimulatorService simulator, SmartTruckGpsReader smartTruck, ShipmentImportService importer,
                              @Value("${gps.tms.time-zone:Asia/Kolkata}") String zone) {
        this.simulator = simulator;
        this.smartTruck = smartTruck;
        this.importer = importer;
        this.zone = ZoneId.of(zone);
    }

    public ShipmentImportService.Report load(PortalUser user) {
        user.requireDeveloper();
        List<ReplayedTrip> trips = simulator.replayedTrips();
        if (trips.isEmpty()) {
            throw new IllegalStateException("Start the fleet simulator (Live -> Fleet simulator) with faithful timing first: "
                    + "the demo shipments are the trips it drives");
        }
        Map<Long, ReplayedTrip> byTrip = new java.util.HashMap<>();
        trips.forEach(t -> byTrip.put(t.tripNumber(), t));
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> row : smartTruck.readTripRows(new ArrayList<>(byTrip.keySet()))) {
            ReplayedTrip trip = byTrip.get(((Number) row.get("i_trip_no")).longValue());
            if (trip == null) {
                continue;
            }
            Duration shift = Duration.between(trip.originalFrom(), trip.replayedFrom());
            List<String> cells = new ArrayList<>(ShipmentColumns.TTA_TRIPS_ORDER.size());
            for (String column : ShipmentColumns.TTA_TRIPS_ORDER) {
                Object value = row.get(column);
                if (LATER.contains(column)) {
                    cells.add(null);
                } else if ("c_trip_status".equals(column)) {
                    cells.add("Open");
                } else if (value instanceof LocalDateTime time) {
                    cells.add(SHIFTED.contains(column) ? TMS_TIME.format(shifted(time, shift)) : TMS_TIME.format(time));
                } else if (value instanceof java.sql.Timestamp time) {
                    LocalDateTime local = time.toLocalDateTime();
                    cells.add(SHIFTED.contains(column) ? TMS_TIME.format(shifted(local, shift)) : TMS_TIME.format(local));
                } else {
                    cells.add(value == null ? null : value.toString());
                }
            }
            rows.add(cells);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("smart_truck has no TMS rows (tta_trips) for the trips being replayed");
        }
        return importer.commitRows("smart_truck demo (" + rows.size() + " replayed trips)", rows, user, "SMART_TRUCK");
    }

    private LocalDateTime shifted(LocalDateTime tmsTime, Duration shift) {
        Instant moved = tmsTime.atZone(zone).toInstant().plus(shift);
        return LocalDateTime.ofInstant(moved, zone);
    }
}
