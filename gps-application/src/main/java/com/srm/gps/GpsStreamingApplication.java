package com.srm.gps;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The GPS streaming service.
 *
 * Lives in package com.srm.gps so Spring also finds the components, entities and repositories
 * of the gps-connectivity module (package com.srm.gps.connectivity).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GpsStreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(GpsStreamingApplication.class, args);
    }
}
