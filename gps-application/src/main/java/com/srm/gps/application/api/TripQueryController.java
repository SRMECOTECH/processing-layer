package com.srm.gps.application.api;

import com.srm.gps.connectivity.database.repository.GpsStopRepository;
import com.srm.gps.connectivity.database.repository.GpsTripRepository;
import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only API over the trips and stops found by the trip detection (consumer group gps-trip-service).
 *
 *   GET /api/trips?imei=0861000000000001&from=2026-10-19T00:00:00Z&to=2026-10-20T00:00:00Z
 *   GET /api/stops?imei=0861000000000001&from=...&to=...
 *   GET /api/trips/count
 *
 * Returns everything that overlaps the period: a trip that started before "from" but was still going on is included.
 * Without imei: all devices, newest first. Without from/to: the last 24 hours.
 */
@RestController
public class TripQueryController {

    private static final Duration DEFAULT_PERIOD = Duration.ofHours(24);
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAXIMUM_LIMIT = 1000;

    private final GpsTripRepository gpsTripRepository;
    private final GpsStopRepository gpsStopRepository;

    public TripQueryController(GpsTripRepository gpsTripRepository, GpsStopRepository gpsStopRepository) {
        this.gpsTripRepository = gpsTripRepository;
        this.gpsStopRepository = gpsStopRepository;
    }

    @GetMapping("/api/trips")
    public List<TripView> trips(@RequestParam(required = false) String imei,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        Period period = Period.of(from, to);
        var trips = imei == null || imei.isBlank()
                ? gpsTripRepository.findOverlappingAllDevices(period.from(), period.to(), limit(limit))
                : gpsTripRepository.findOverlapping(imei.trim(), period.from(), period.to(), limit(limit));
        return trips.stream().map(TripView::from).toList();
    }

    @GetMapping("/api/stops")
    public List<StopView> stops(@RequestParam(required = false) String imei,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        Period period = Period.of(from, to);
        var stops = imei == null || imei.isBlank()
                ? gpsStopRepository.findOverlappingAllDevices(period.from(), period.to(), limit(limit))
                : gpsStopRepository.findOverlapping(imei.trim(), period.from(), period.to(), limit(limit));
        return stops.stream().map(StopView::from).toList();
    }

    @GetMapping("/api/trips/count")
    public Map<String, Long> countTripsAndStops() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("openTrips", gpsTripRepository.countByStatus("OPEN"));
        counts.put("closedTrips", gpsTripRepository.countByStatus("CLOSED"));
        counts.put("openStops", gpsStopRepository.countByStatus("OPEN"));
        counts.put("closedStops", gpsStopRepository.countByStatus("CLOSED"));
        return counts;
    }

    private static Limit limit(int requested) {
        return Limit.of(Math.clamp(requested, 1, MAXIMUM_LIMIT));
    }

    private record Period(Instant from, Instant to) {
        static Period of(Instant from, Instant to) {
            Instant end = to != null ? to : Instant.now();
            return new Period(from != null ? from : end.minus(DEFAULT_PERIOD), end);
        }
    }
}
