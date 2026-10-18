package com.srm.gps.application.simulator;

import com.srm.gps.configuration.message.RawGpsFrame;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controls the GPS device simulator.
 *
 *   POST /api/simulator/start?vehicleCount=150&pingsPerVehicle=500
 *   POST /api/simulator/stop
 *   GET  /api/simulator/status
 *   POST /api/simulator/send-single-frame   (body: a raw frame as JSON)
 */
@RestController
@RequestMapping("/api/simulator")
public class GpsDeviceSimulatorController {

    private final GpsDeviceSimulatorService simulatorService;
    private final GpsDeviceSimulatorSettings settings;

    public GpsDeviceSimulatorController(GpsDeviceSimulatorService simulatorService, GpsDeviceSimulatorSettings settings) {
        this.simulatorService = simulatorService;
        this.settings = settings;
    }

    @PostMapping("/start")
    public SimulationStatus start(@RequestParam(required = false) Integer vehicleCount,
                                  @RequestParam(required = false) Integer pingsPerVehicle) {
        return simulatorService.startFleet(
                vehicleCount != null ? vehicleCount : settings.vehicleCount(),
                pingsPerVehicle != null ? pingsPerVehicle : settings.pingsPerVehicle());
    }

    @PostMapping("/stop")
    public SimulationStatus stop() {
        return simulatorService.stopFleet();
    }

    @GetMapping("/status")
    public SimulationStatus status() {
        return simulatorService.currentStatus();
    }

    @PostMapping("/send-single-frame")
    public ResponseEntity<String> sendSingleFrame(@RequestBody RawGpsFrame frame) {
        simulatorService.sendSingleFrame(frame);
        return ResponseEntity.accepted().body("Frame sent into the " + simulatorService.currentStatus().processingFlow() + " flow");
    }
}
