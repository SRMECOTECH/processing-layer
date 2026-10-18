package com.srm.gps.application.engine;

import com.srm.gps.application.engine.EngineViews.DeviceLoadView;
import com.srm.gps.application.engine.EngineViews.EngineOverview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The engine API behind the dashboard at http://localhost:8080
 *
 *   GET /api/engine/overview             producers, topics, consumers (load + lag), PostgreSQL, Redis, simulator
 *   GET /api/engine/devices?imei=0867    devices whose IMEI contains the text, with messages per producer/consumer
 *   GET /api/engine/devices/{imei}       one device: its partition, its load per stage, its live position
 */
@RestController
@RequestMapping("/api/engine")
public class EngineController {

    private final EngineOverviewService engineOverviewService;

    public EngineController(EngineOverviewService engineOverviewService) {
        this.engineOverviewService = engineOverviewService;
    }

    @GetMapping("/overview")
    public EngineOverview overview() {
        return engineOverviewService.overview();
    }

    @GetMapping("/devices")
    public List<DeviceLoadView> devices(@RequestParam(required = false) String imei) {
        return engineOverviewService.devices(imei);
    }

    @GetMapping("/devices/{imei}")
    public DeviceLoadView device(@PathVariable String imei) {
        return engineOverviewService.device(imei);
    }
}
