package com.srm.gps.application.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/admin/reset?restartFleet=true  - "Empty DB" for demo practice (see {@link DemoResetService}).
 * Answers 403 when gps.admin.reset-enabled is false.
 */
@RestController
public class DemoResetController {

    private final DemoResetService resetService;
    private final boolean enabled;

    public DemoResetController(DemoResetService resetService, @Value("${gps.admin.reset-enabled:true}") boolean enabled) {
        this.resetService = resetService;
        this.enabled = enabled;
    }

    @PostMapping("/api/admin/reset")
    public ResponseEntity<?> reset(@RequestParam(defaultValue = "false") boolean restartFleet,
                                   @RequestParam(defaultValue = "true") boolean keepReports) {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "The demo reset is switched off (gps.admin.reset-enabled=false)"));
        }
        return ResponseEntity.ok(resetService.reset(restartFleet, keepReports));
    }
}
