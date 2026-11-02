package com.srm.gps.application.route;

import com.srm.gps.processing.route.RouteSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RouteConfiguration {

    /** gps.modules.route-deviation.* (deviation, wrong direction, ETA) and gps.modules.waypoints.* (stops), see application.yml. */
    @Bean
    RouteSettings routeSettings(Environment environment) {
        return RouteSettings.from(key -> {
            String value = environment.getProperty("gps.modules.route-deviation." + key);
            return value != null ? value : environment.getProperty("gps.modules.waypoints." + key);
        });
    }
}
