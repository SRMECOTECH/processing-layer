package com.srm.gps.application.road;

import com.srm.gps.processing.road.RoadSpeedTable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Road Intelligence (docs/ROAD_INTELLIGENCE.md): the road library learned with Valhalla, road speed limits used by the
 * speed-violation module, and the day-end road report.
 *
 *   GET    /api/roads/status                      library size, Valhalla address, the newest learning runs
 *   GET    /api/roads/network                     every stored piece with its line, class and limit (the road map)
 *   POST   /api/roads/learn?trip=28860462         map-match a smart_truck trip on Valhalla and keep its roads
 *   POST   /api/roads/match-live                  match the live readings now instead of at the next 2-minute run
 *   GET    /api/roads?q=NH19&limit=200            the known roads with their limit and its source
 *   GET    /api/roads/pieces?ref=NH19&name=       every stored piece of one road
 *   PUT    /api/roads/pieces/{id}/limit           body {limitKmph, note}: a hand-set limit on one piece (null clears it)
 *   PUT    /api/roads/limit                       body {ref, name, limitKmph, note}: on every piece of one road
 *   GET    /api/roads/locate?lat=&lon=            the road under a point and its limit
 *   GET    /api/roads/limits                      the limit table by road class and area
 *   PUT    /api/roads/limits                      body a table row: create or change it
 *   DELETE /api/roads/limits?roadClass=&area=&stateCode=   a state's own row
 *   GET    /api/roads/report/days                 the days of the road report
 *   GET    /api/roads/report?day=&imei=           the day-end road and speed report
 *   POST   /api/roads/report/rebuild?day=         rebuild a day's road rows now
 */
@RestController
@RequestMapping("/api/roads")
public class RoadController {

    private final RoadLibrary library;
    private final RoadLearningService learning;
    private final RoadDayReportService reports;
    private final RoadAnalysisQueries analysis;
    private final RoadRecords records;

    /** The console user the dashboard reports on every call it forwards (for the history of limit changes). */
    private static final String USER_HEADER = "X-Dashboard-User";

    public RoadController(RoadLibrary library, RoadLearningService learning, RoadDayReportService reports,
                          RoadAnalysisQueries analysis, RoadRecords records) {
        this.library = library;
        this.learning = learning;
        this.reports = reports;
        this.analysis = analysis;
        this.records = records;
    }

    /** Points on the road (junctions, toll booths, gates, borders) in totals and lists (V14). */
    @GetMapping("/points")
    public Map<String, Object> points() {
        return analysis.points();
    }

    /** The points to draw on the console's maps: toll booths, gates, borders, signals, junctions (V14). */
    @GetMapping("/points/map")
    public List<Map<String, Object>> mapPoints() {
        return analysis.mapPoints();
    }

    /** Vehicle events by road and type (V14): where stoppages, over-speeding and the rest happen. */
    @GetMapping("/events-by-road")
    public Map<String, Object> eventsByRoad(@RequestParam(required = false) String imei) {
        return analysis.eventsByRoad(blankToNull(imei));
    }

    /** The newest map-matching runs and their totals (V14). */
    @GetMapping("/match-runs")
    public Map<String, Object> matchRuns(@RequestParam(defaultValue = "50") int limit) {
        return analysis.matchRuns(Math.max(1, Math.min(limit, 500)));
    }

    /** The history of speed-limit changes, newest first (V14). */
    @GetMapping("/limit-changes")
    public List<Map<String, Object>> limitChanges(@RequestParam(defaultValue = "100") int limit) {
        return analysis.limitChanges(Math.max(1, Math.min(limit, 1000)));
    }

    /** What Valhalla told us about the learned roads, in totals (V13). */
    @GetMapping("/attributes")
    public Map<String, Object> attributes() {
        return analysis.attributes();
    }

    /** Everything stored about one road piece, the whole Valhalla edge included. */
    @GetMapping("/pieces/{id}")
    public Map<String, Object> piece(@org.springframework.web.bind.annotation.PathVariable long id) {
        Map<String, Object> piece = analysis.piece(id);
        if (piece.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No road piece " + id);
        }
        return piece;
    }

    /** Road passes, newest first: each time a vehicle drove a road (V13). */
    @GetMapping("/passes")
    public List<Map<String, Object>> passes(@RequestParam(required = false) String imei, @RequestParam(defaultValue = "200") int limit) {
        return analysis.passes(blankToNull(imei), Math.max(1, Math.min(limit, 2000)));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return learning.status();
    }

    @PostMapping("/learn")
    public Map<String, Object> learn(@RequestParam long trip) {
        boolean started = learning.learnSmartTruckTrip(trip);
        Map<String, Object> answer = new LinkedHashMap<>(learning.status());
        answer.put("started", started);
        return answer;
    }

    @PostMapping("/match-live")
    public Map<String, Object> matchLive() {
        learning.matchLiveReadings();
        return learning.status();
    }

    @GetMapping
    public List<Map<String, Object>> roads(@RequestParam(required = false) String q, @RequestParam(defaultValue = "200") int limit) {
        return library.roads(q, Math.max(1, Math.min(limit, 2000)));
    }

    @GetMapping("/network")
    public List<Map<String, Object>> network() {
        List<Map<String, Object>> pieces = library.network();
        Map<Long, Object[]> flags = analysis.mapFlags();
        for (Map<String, Object> piece : pieces) {
            Object[] f = flags.get((Long) piece.get("id"));
            if (f != null) {
                piece.put("bridge", f[0]);
                piece.put("oneWay", f[1]);
                piece.put("curvature", f[2]);
            }
        }
        return pieces;
    }

    @GetMapping("/pieces")
    public List<Map<String, Object>> pieces(@RequestParam(required = false) String ref, @RequestParam(required = false) String name) {
        return library.pieces(blankToNull(ref), blankToNull(name));
    }

    public record LimitChange(String ref, String name, Integer limitKmph, String note) {
    }

    @PutMapping("/pieces/{id}/limit")
    public Map<String, Object> pieceLimit(@org.springframework.web.bind.annotation.PathVariable long id, @RequestBody LimitChange change,
                                          @RequestHeader(value = USER_HEADER, required = false) String user) {
        checkLimit(change.limitKmph());
        Map<String, Object> before = analysis.pieceLimit(id);
        if (!library.setManualLimit(id, change.limitKmph(), change.note())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No road piece " + id);
        }
        records.limitChanged(user, "PIECE", "piece " + id + " · " + before.get("label"), 1, (Integer) before.get("limit"),
                change.limitKmph(), null, null, change.note());
        return Map.of("id", id, "limitKmph", change.limitKmph() == null ? "cleared" : change.limitKmph());
    }

    @PutMapping("/limit")
    public Map<String, Object> roadLimit(@RequestBody LimitChange change,
                                         @RequestHeader(value = USER_HEADER, required = false) String user) {
        checkLimit(change.limitKmph());
        Integer before = analysis.roadLimit(blankToNull(change.ref()), blankToNull(change.name()));
        int pieces = library.setManualLimitForRoad(blankToNull(change.ref()), blankToNull(change.name()), change.limitKmph(), change.note());
        if (pieces == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored road with that number and name");
        }
        String road = String.join(" ", java.util.stream.Stream.of(blankToNull(change.ref()), blankToNull(change.name()))
                .filter(java.util.Objects::nonNull).toList());
        records.limitChanged(user, "ROAD", road.isEmpty() ? "unnamed road" : road, pieces, before, change.limitKmph(), null, null,
                change.note());
        return Map.of("pieces", pieces, "limitKmph", change.limitKmph() == null ? "cleared" : change.limitKmph());
    }

    @GetMapping("/locate")
    public Map<String, Object> locate(@RequestParam double lat, @RequestParam double lon) {
        RoadLibrary.RoadAt at = library.locate(lat, lon);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("found", at != null);
        if (at != null) {
            answer.put("id", at.road().id());
            answer.put("label", at.road().label());
            answer.put("roadClass", at.road().roadClass());
            answer.put("stateCode", at.road().stateCode());
            answer.put("lanes", at.road().laneCount());
            answer.put("density", at.road().density());
            answer.put("distanceM", Math.round(at.distanceMeters() * 10) / 10.0);
            answer.put("limitKmph", at.limit() == null ? null : at.limit().kmph());
            answer.put("limitSource", at.limit() == null ? null : at.limit().source().name());
            answer.put("area", at.limit() == null ? null : at.limit().area().name());
            answer.put("details", analysis.piece(at.road().id()));
        }
        return answer;
    }

    @GetMapping("/limits")
    public Map<String, Object> limits() {
        RoadSpeedTable table = library.loadTable();
        return Map.of("rows", table.rows(), "townDensityFrom", table.townDensityFrom());
    }

    @PutMapping("/limits")
    public Map<String, Object> saveLimit(@RequestBody RoadSpeedTable.Row row,
                                         @RequestHeader(value = USER_HEADER, required = false) String user) {
        String state = row.stateCode() == null || row.stateCode().isBlank() ? RoadSpeedTable.ALL_STATES
                : row.stateCode().toUpperCase(java.util.Locale.ROOT);
        String roadClass = row.roadClass() == null ? "" : row.roadClass().toLowerCase(java.util.Locale.ROOT);
        Integer[] before = analysis.tableRow(roadClass, row.area() == null ? "" : row.area().name(), state);
        try {
            library.saveTableRow(row);
            records.limitChanged(user, "TABLE_ROW", roadClass + "/" + row.area() + "/" + state, null, before[0], row.limitKmph(),
                    before[1], row.multiLaneLimitKmph(), null);
        } catch (IllegalArgumentException wrong) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, wrong.getMessage());
        }
        return limits();
    }

    @DeleteMapping("/limits")
    public Map<String, Object> deleteLimit(@RequestParam String roadClass, @RequestParam String area, @RequestParam String stateCode,
                                           @RequestHeader(value = USER_HEADER, required = false) String user) {
        Integer[] before = analysis.tableRow(roadClass, area, stateCode);
        try {
            if (library.deleteTableRow(roadClass, area, stateCode)) {
                records.limitChanged(user, "TABLE_ROW_DELETED", roadClass + "/" + area + "/" + stateCode, null, before[0], null,
                        before[1], null, null);
            }
        } catch (IllegalArgumentException wrong) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, wrong.getMessage());
        }
        return limits();
    }

    @GetMapping("/report/days")
    public List<Map<String, Object>> days() {
        return reports.days(62);
    }

    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam(required = false) LocalDate day, @RequestParam(required = false) String imei) {
        return reports.report(day != null ? day : reports.today(), blankToNull(imei));
    }

    @PostMapping("/report/rebuild")
    public Map<String, Object> rebuild(@RequestParam(required = false) LocalDate day) {
        LocalDate d = day != null ? day : reports.today();
        return Map.of("day", d, "vehicles", reports.summarize(d));
    }

    private static void checkLimit(Integer limit) {
        if (limit != null && (limit < 5 || limit > 150)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A limit is 5 to 150 km/h");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
