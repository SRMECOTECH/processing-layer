package com.srm.gps.application.safety;

import com.srm.gps.application.safety.DataSafetyChecks.Check;
import com.srm.gps.application.safety.DataSafetyChecks.Status;
import com.srm.gps.application.safety.DataSafetyService.Report;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * dataSafety in /actuator/health: DOWN when data was lost or can be lost now (a FAIL check), UP otherwise, with every
 * warning listed. Kafka being down is a warning, not DOWN: incoming data waits in the journal.
 */
@Component
public class DataSafetyHealthIndicator implements HealthIndicator {

    private final DataSafetyService safety;

    public DataSafetyHealthIndicator(DataSafetyService safety) {
        this.safety = safety;
    }

    @Override
    public Health health() {
        Report report = safety.report();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("verdict", report.verdict());
        for (Check check : report.checks()) {
            if (check.status() != Status.OK) {
                details.put(check.id(), check.status() + ": " + check.detail());
            }
        }
        return (report.verdict() == Status.FAIL ? Health.down() : Health.up()).withDetails(details).build();
    }
}
