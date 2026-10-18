package com.srm.gps.application.api;

import com.srm.gps.connectivity.database.repository.GpsPositionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only API to see what reached the database.
 *
 *   GET /api/gps-positions/count
 *   GET /api/gps-positions/recently-stored
 *   GET /api/vehicles/{registrationNo}/latest-position
 */
@RestController
public class GpsPositionQueryController {

    private final GpsPositionRepository gpsPositionRepository;

    public GpsPositionQueryController(GpsPositionRepository gpsPositionRepository) {
        this.gpsPositionRepository = gpsPositionRepository;
    }

    @GetMapping("/api/gps-positions/count")
    public Map<String, Long> countStoredPositions() {
        return Map.of("storedPositions", gpsPositionRepository.count());
    }

    @GetMapping("/api/gps-positions/recently-stored")
    public List<GpsPositionView> recentlyStoredPositions() {
        return gpsPositionRepository.findTop20ByOrderByStoredAtDesc().stream()
                .map(GpsPositionView::from)
                .toList();
    }

    @GetMapping("/api/vehicles/{registrationNo}/latest-position")
    public ResponseEntity<GpsPositionView> latestPositionOfVehicle(@PathVariable String registrationNo) {
        return gpsPositionRepository.findFirstByVehicleRegistrationNoOrderByRecordedAtDesc(registrationNo)
                .map(GpsPositionView::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
