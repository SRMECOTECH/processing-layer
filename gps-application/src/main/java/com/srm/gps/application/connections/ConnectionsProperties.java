package com.srm.gps.application.connections;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * gps.connections.* in application.yml: the defaults of the outside connections. What is saved from the console
 * (Developer -> Connections, table connection_setting) wins over these. Every value is optional.
 *
 * @param valhallaUrl           the Valhalla routing engine (default http://192.168.137.1:8002, the HP home server)
 * @param ingestionApiUrl       the HP's ingestion API, shown and checked only (default http://192.168.137.1:8000)
 * @param homeServerRedisUrl    the "HP home server" choice for Redis (default redis://192.168.137.1:6379/1: database 1,
 *                              because the ingestion API uses database 0 there)
 * @param redisCommandTimeout   a Redis call that takes longer fails (default 5 s; Redis may be across the Wi-Fi)
 * @param redisConnectTimeout   default 3 s
 * @param httpTimeout           calls to Valhalla and the ingestion API (default 5 s)
 */
@ConfigurationProperties("gps.connections")
public record ConnectionsProperties(String valhallaUrl, String ingestionApiUrl, String homeServerRedisUrl,
                                    Duration redisCommandTimeout, Duration redisConnectTimeout, Duration httpTimeout) {

    public String valhallaUrlOrDefault() {
        return valhallaUrl != null && !valhallaUrl.isBlank() ? valhallaUrl : "http://192.168.137.1:8002";
    }

    public String ingestionApiUrlOrDefault() {
        return ingestionApiUrl != null && !ingestionApiUrl.isBlank() ? ingestionApiUrl : "http://192.168.137.1:8000";
    }

    public String homeServerRedisUrlOrDefault() {
        return homeServerRedisUrl != null && !homeServerRedisUrl.isBlank() ? homeServerRedisUrl : "redis://192.168.137.1:6379/1";
    }

    public Duration redisCommandTimeoutOrDefault() {
        return redisCommandTimeout != null ? redisCommandTimeout : Duration.ofSeconds(5);
    }

    public Duration redisConnectTimeoutOrDefault() {
        return redisConnectTimeout != null ? redisConnectTimeout : Duration.ofSeconds(3);
    }

    public Duration httpTimeoutOrDefault() {
        return httpTimeout != null ? httpTimeout : Duration.ofSeconds(5);
    }
}
