package com.srm.gps.route.engine;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * "routingEngine" in http://localhost:8090/actuator/health: UP with the map's data date when GraphHopper answers,
 * DOWN while it is stopped or still importing the map.
 */
@Component("routingEngine")
public class RoutingEngineHealthIndicator implements HealthIndicator {

    private final GraphHopperClient engine;

    public RoutingEngineHealthIndicator(GraphHopperClient engine) {
        this.engine = engine;
    }

    @Override
    public Health health() {
        try {
            Map<String, Object> info = engine.info();
            return Health.up()
                    .withDetail("url", engine.url())
                    .withDetail("version", String.valueOf(info.get("version")))
                    .withDetail("dataDate", String.valueOf(info.get("data_date")))
                    .build();
        } catch (RoutingEngineException problem) {
            return Health.down().withDetail("url", engine.url()).withDetail("error", problem.getMessage()).build();
        }
    }
}
