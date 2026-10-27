package com.srm.gps.application.safety;

import com.srm.gps.application.safety.DataSafetyService.Report;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/data-safety - every check, the platform topics, every consumer group's position, the journal, the
 * dead-letter and rejected-frame archives. The same verdict is dataSafety in /actuator/health.
 */
@RestController
public class DataSafetyController {

    private final DataSafetyService safety;

    public DataSafetyController(DataSafetyService safety) {
        this.safety = safety;
    }

    @GetMapping("/api/data-safety")
    public Report report() {
        return safety.report();
    }
}
