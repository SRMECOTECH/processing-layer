package com.srm.gps.application.report;

import com.srm.gps.processing.report.FuelAssumptions;
import com.srm.gps.processing.report.FuelEstimate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What /api/reports/* answers. Distances in km, times in seconds, money in rupees. */
public final class ReportViews {

    private ReportViews() {
    }

    /**
     * One vehicle over the report's period (or the whole fleet, then imei is null).
     *
     * @param days                days with readings in the period
     * @param movingSeconds       time driving (gaps between readings while MOVING)
     * @param stoppedSeconds      time standing (gaps between readings while STOPPED)
     * @param idleSeconds         standing with the engine on past the idling limit (IDLING episodes)
     * @param stopSeconds         time inside detected stops (gps_stop)
     * @param engineOnStops       stops with the engine running (STOPPAGE episodes)
     * @param fastKm              km driven above the economical speed
     * @param avgMovingSpeedKmph  distance / driving time
     * @param utilisationPercent  driving time / (driving + standing time)
     * @param economicalSpeedKmph the speed the fast km were counted against
     */
    public record VehicleRow(String imei, String registration, String tenant, int vehicles, int days, long readings,
                             Instant firstSeen, Instant lastSeen, double distanceKm, long movingSeconds, long stoppedSeconds,
                             long idleSeconds, int stops, long stopSeconds, int engineOnStops, int trips, int maxSpeedKmph,
                             double avgMovingSpeedKmph, double fastKm, int economicalSpeedKmph, int overspeedEvents,
                             int harshEvents, double utilisationPercent, FuelEstimate fuel) {
    }

    /** The fleet on one day of a month report. */
    public record DayRow(LocalDate day, int vehicles, double distanceKm, long movingSeconds, long idleSeconds, int stops,
                         int trips, int overspeedEvents, FuelEstimate fuel) {
    }

    /**
     * A day or a month report.
     *
     * @param summarizedAt when the newest summary row of the period was computed
     * @param vehicles     one row per vehicle with readings, most savable rupees first
     * @param days         month report: the fleet per day (empty for a day report)
     */
    public record FleetReport(String kind, LocalDate from, LocalDate to, String zone, String tenant, String imei,
                              FuelAssumptions assumptions, Instant summarizedAt, VehicleRow totals, List<VehicleRow> vehicles,
                              List<DayRow> days) {
    }

    /**
     * One trip or stop of a vehicle's day, in time order.
     *
     * @param kind         TRIP | STOP
     * @param engine       stops: ON (STOPPAGE episode), OFF (PARKED), UNKNOWN
     * @param idleSeconds  stops: idling inside the stop
     */
    public record TimelineEntry(String kind, Instant start, Instant end, long seconds, Double distanceKm, Integer maxSpeedKmph,
                                Double avgSpeedKmph, Double latitude, Double longitude, String engine, long idleSeconds,
                                String status) {
    }

    public record EventEntry(String type, String severity, String status, Instant startedAt, Instant endedAt, Long seconds) {
    }

    /** Reports -> Vehicle day sheet: what one vehicle did on one day. */
    public record VehicleDaySheet(LocalDate day, String zone, String imei, VehicleRow summary, List<TimelineEntry> timeline,
                                  List<EventEntry> events, FuelAssumptions assumptions) {
    }

    public record TenantOption(String code, String name) {
    }

    public record VehicleOption(String imei, String registration, String tenant) {
    }

    /** The choices of the report filters. */
    public record Filters(String zone, LocalDate today, List<TenantOption> tenants, List<VehicleOption> vehicles) {
    }

    /** POST /api/reports/refresh */
    public record Refresh(LocalDate day, int vehicles, Instant at) {
    }

    /** PUT /api/reports/assumptions: the values to change; a missing one keeps its current value. */
    public record AssumptionsChange(String fuelType, Double pricePerLitre, Double mileageKmPerLitre, Double idleLitresPerHour,
                                    Integer economicalSpeedKmph, Double fastDrivingPenaltyPercent, Double co2KgPerLitre) {

        /** @throws IllegalArgumentException when a value cannot be right (a mileage of 0, a negative price ...) */
        public FuelAssumptions applyTo(FuelAssumptions current) {
            return new FuelAssumptions(
                    fuelType != null ? fuelType : current.fuelType(),
                    pricePerLitre != null ? pricePerLitre : current.pricePerLitre(),
                    mileageKmPerLitre != null ? mileageKmPerLitre : current.mileageKmPerLitre(),
                    idleLitresPerHour != null ? idleLitresPerHour : current.idleLitresPerHour(),
                    economicalSpeedKmph != null ? economicalSpeedKmph : current.economicalSpeedKmph(),
                    fastDrivingPenaltyPercent != null ? fastDrivingPenaltyPercent : current.fastDrivingPenaltyPercent(),
                    co2KgPerLitre != null ? co2KgPerLitre : current.co2KgPerLitre());
        }
    }
}
