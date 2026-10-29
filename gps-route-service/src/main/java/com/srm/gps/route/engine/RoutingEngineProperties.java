package com.srm.gps.route.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the routing engine is and how long to wait for it (properties starting with "gps.routing.engine").
 *
 * @param url                  GraphHopper's address: http://localhost:8989 on Windows, http://graphhopper:8989 in Docker
 * @param defaultProfile       profile used when a request names none (truck: our fleet)
 * @param connectTimeoutMillis how long to wait for a connection
 * @param requestTimeoutMillis how long to wait for an answer (a Dijkstra across a region takes seconds)
 */
@ConfigurationProperties(prefix = "gps.routing.engine")
public record RoutingEngineProperties(
        @DefaultValue("http://localhost:8989") String url,
        @DefaultValue("truck") String defaultProfile,
        @DefaultValue("3000") long connectTimeoutMillis,
        @DefaultValue("60000") long requestTimeoutMillis) {
}
