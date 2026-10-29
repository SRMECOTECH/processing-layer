package com.srm.gps.application.report;

import com.srm.gps.application.report.ReportViews.Filters;
import com.srm.gps.application.report.ReportViews.FleetReport;
import com.srm.gps.application.report.ReportViews.Refresh;
import com.srm.gps.application.report.ReportViews.VehicleDaySheet;
import com.srm.gps.processing.report.FuelAssumptions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * End-of-day and end-of-month reports for every vehicle, with fuel and cost estimates (Reports in the console).
 * Days are midnight to midnight in gps.reports.zone (IST by default).
 *
 *   GET  /api/reports/filters                              companies, vehicles, today
 *   GET  /api/reports/day?date=2026-10-29&tenant=&imei=    every vehicle on one day
 *   GET  /api/reports/month?month=2026-10&tenant=&imei=    every vehicle over a month, and the fleet per day
 *   GET  /api/reports/vehicle-day?imei=&date=              one vehicle's day: trips and stops in order, events
 *   POST /api/reports/refresh?date=                        rebuild a day's summaries now (otherwise every 5 minutes)
 *   GET  /api/reports/assumptions                          fuel price, mileage, idle burn ... behind the estimates
 *   PUT  /api/reports/assumptions                          change them; every report is priced again at once
 */
@RestController
public class ReportController {

    private final ReportService reports;
    private final ReportSettingStore settings;
    private final DaySummaryJob job;

    public ReportController(ReportService reports, ReportSettingStore settings, DaySummaryJob job) {
        this.reports = reports;
        this.settings = settings;
        this.job = job;
    }

    @GetMapping("/api/reports/filters")
    public Filters filters() {
        return reports.filters();
    }

    @GetMapping("/api/reports/day")
    public FleetReport day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam(required = false) String tenant, @RequestParam(required = false) String imei) {
        return reports.day(date, tenant, imei);
    }

    @GetMapping("/api/reports/month")
    public FleetReport month(@RequestParam(required = false) String month, @RequestParam(required = false) String tenant,
                             @RequestParam(required = false) String imei) {
        return reports.month(month == null || month.isBlank() ? null : YearMonth.parse(month.trim()), tenant, imei);
    }

    @GetMapping("/api/reports/vehicle-day")
    public VehicleDaySheet vehicleDay(@RequestParam(required = false) String imei,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.vehicleDay(imei, date);
    }

    @PostMapping("/api/reports/refresh")
    public Refresh refresh(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reports.refresh(date);
    }

    @GetMapping("/api/reports/assumptions")
    public FuelAssumptions assumptions() {
        return settings.assumptions();
    }

    @PutMapping("/api/reports/assumptions")
    public FuelAssumptions saveAssumptions(@RequestBody ReportViews.AssumptionsChange body) {
        FuelAssumptions current = settings.assumptions();
        int economicalBefore = current.economicalSpeedKmph();
        FuelAssumptions saved = settings.save(body.applyTo(current));
        if (saved.economicalSpeedKmph() != economicalBefore) {
            // the km above the economical speed are counted while summarizing: count them again for every day
            job.recomputeAllSoon();
        }
        return saved;
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    ResponseEntity<Map<String, String>> badRequest(RuntimeException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }
}
