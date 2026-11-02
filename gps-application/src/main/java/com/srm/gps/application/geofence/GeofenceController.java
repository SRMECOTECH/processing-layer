package com.srm.gps.application.geofence;

import com.srm.gps.application.geofence.GeofenceVisitStore.Filter;
import com.srm.gps.application.geofence.GeofenceVisitStore.Visit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The geofencing module's API (console, GPS provider view; the portals use /api/portal/geofence-report).
 *
 *   GET  /api/geofences                           every fence, with the vehicles inside it now
 *   GET  /api/geofences/engine                    the grid index and the detection rules in force
 *   GET  /api/geofences/locate?lat=&lon=          which fences a point is inside
 *   PUT  /api/geofences/{code}                    create or change a fence (circle or polygon), body FenceChange
 *   POST /api/geofences/{id}/active?value=false   switch a fence off (its history stays) or on again
 *   POST /api/geofences/import/smart-truck        copy smart_truck's geofence master (fences edited here are kept)
 *   GET  /api/geofences/live?fenceId=             vehicles inside a fence now, longest first
 *   GET  /api/geofences/visits?imei=&vehicle=&fenceId=&limit=   the newest visits and passes
 *   GET  /api/geofences/report/day?date=&tenant=&imei=&vehicle=&fenceId=   the day-wise geofence report
 *   GET  /api/geofences/report/day.csv?date=...   the same day's visits as a spreadsheet
 */
@RestController
@RequestMapping("/api/geofences")
public class GeofenceController {

    private static final int MAX_LIMIT = 2_000;

    private final GeofenceService fences;
    private final GeofenceVisitStore visits;
    private final GeofenceReportService reports;

    public GeofenceController(GeofenceService fences, GeofenceVisitStore visits, GeofenceReportService reports) {
        this.fences = fences;
        this.visits = visits;
        this.reports = reports;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return fences.fences();
    }

    @GetMapping("/engine")
    public Map<String, Object> engine() {
        return fences.engine();
    }

    @GetMapping("/locate")
    public List<Map<String, Object>> locate(@RequestParam double lat, @RequestParam double lon) {
        return fences.locate(lat, lon);
    }

    @PutMapping("/{code}")
    public Map<String, Object> save(@PathVariable String code, @RequestBody GeofenceService.FenceChange change) {
        return fences.save(code, change);
    }

    @PostMapping("/{id}/active")
    public Map<String, Object> active(@PathVariable long id, @RequestParam boolean value) {
        fences.setActive(id, value);
        return Map.of("id", id, "active", value);
    }

    @PostMapping("/import/smart-truck")
    public Map<String, Object> importSmartTruck() {
        return fences.importFromSmartTruck();
    }

    @GetMapping("/live")
    public List<Visit> live(@RequestParam(required = false) Long fenceId, @RequestParam(required = false) String tenant,
                            @RequestParam(defaultValue = "500") int limit) {
        return visits.open(new Filter(null, null, fenceId, tenant, null), Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/visits")
    public List<Visit> visits(@RequestParam(required = false) String imei, @RequestParam(required = false) String vehicle,
                              @RequestParam(required = false) Long fenceId, @RequestParam(required = false) String tenant,
                              @RequestParam(defaultValue = "200") int limit) {
        return visits.recent(new Filter(imei, vehicle, fenceId, tenant, null), Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/report/day")
    public Map<String, Object> day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false) String tenant, @RequestParam(required = false) String imei,
                                   @RequestParam(required = false) String vehicle, @RequestParam(required = false) Long fenceId) {
        return reports.day(date == null ? reports.today() : date, new Filter(imei, vehicle, fenceId, tenant, null));
    }

    @GetMapping(value = "/report/day.csv", produces = "text/csv")
    public ResponseEntity<byte[]> dayCsv(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                         @RequestParam(required = false) String tenant, @RequestParam(required = false) String imei,
                                         @RequestParam(required = false) String vehicle, @RequestParam(required = false) Long fenceId) {
        LocalDate day = date == null ? reports.today() : date;
        return csv(day, reports.dayVisits(day, new Filter(imei, vehicle, fenceId, tenant, null)), reports.zone());
    }

    /** The visits of a day as CSV, times in the report's zone: what a transporter opens in Excel. */
    public static ResponseEntity<byte[]> csv(LocalDate day, List<Visit> rows, ZoneId zone) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        StringBuilder out = new StringBuilder("vehicle,imei,company,fence,role,kind,entered,exited,stay_minutes,still_inside,over_limit,uncertain_minutes,inferred\n");
        for (Visit v : rows) {
            out.append(cell(v.vehicleRegistrationNo())).append(',').append(cell(v.imei())).append(',').append(cell(v.tenantCode())).append(',')
                    .append(cell(v.placeName())).append(',').append(v.placeRole()).append(',').append(v.kind()).append(',')
                    .append(time.format(v.enteredAt())).append(',').append(v.exitedAt() == null ? "" : time.format(v.exitedAt())).append(',')
                    .append(v.minutes()).append(',').append(v.open()).append(',').append(v.dwellAlertAt() != null).append(',')
                    .append(v.uncertaintyMinutes()).append(',').append(v.inferred()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"geofence-visits-" + day + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@") ? "'" + value : value;
        return safe.contains(",") || safe.contains("\"") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }
}
