package com.srm.gps.route;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The route service: plans routes on the India road map through the routing engine (GraphHopper, see
 * infrastructure/routing/graphhopper) and - next - watches every trip against its planned route:
 * deviation, recalculation and reassessment (docs/ROUTE_INTELLIGENCE_PLAN.md).
 *
 * Its own application next to the GPS streaming service, so the routing engine never holds up ingestion
 * and route work can be restarted and scaled on its own.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GpsRouteServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpsRouteServiceApplication.class, args);
    }
}
