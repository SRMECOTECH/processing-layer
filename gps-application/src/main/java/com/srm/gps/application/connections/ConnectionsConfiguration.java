package com.srm.gps.application.connections;

import com.srm.gps.connectivity.http.JsonHttp;
import com.srm.gps.connectivity.ingestionapi.IngestionApiClient;
import com.srm.gps.connectivity.redis.RedisTarget;
import com.srm.gps.connectivity.redis.SwitchableRedisConnectionFactory;
import com.srm.gps.connectivity.valhalla.ValhallaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * The outside connections whose address can change while the service runs (Developer -> Connections).
 *
 * Redis: this factory replaces Spring Boot's own, so StringRedisTemplate, the health check and every consumer use it.
 * At start it points where the console last switched it (connection_setting), otherwise at spring.data.redis.*.
 */
@Configuration
public class ConnectionsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionsConfiguration.class);

    /** This laptop's Redis (spring.data.redis.*: the compose service "redis", or localhost): the default. */
    @Bean
    RedisTarget localRedisTarget(@Value("${spring.data.redis.host:localhost}") String host,
                                 @Value("${spring.data.redis.port:6379}") int port,
                                 @Value("${spring.data.redis.database:0}") int database) {
        return new RedisTarget(host, port, database);
    }

    @Bean
    SwitchableRedisConnectionFactory redisConnectionFactory(RedisTarget localRedisTarget, ConnectionSettingStore settings,
                                                            ConnectionsProperties properties) {
        Optional<ConnectionSettingStore.Saved> saved = settings.find(ConnectionSettingStore.REDIS);
        RedisTarget target = localRedisTarget;
        if (saved.isPresent()) {
            try {
                target = RedisTarget.parse(saved.get().url());
                LOG.info("Redis: {} (chosen in the console on {})", target, saved.get().updatedAt());
            } catch (IllegalArgumentException problem) {
                LOG.warn("Ignoring the saved Redis address '{}': {}. Using {}", saved.get().url(), problem.getMessage(), target);
            }
        } else {
            LOG.info("Redis: {} (the default, spring.data.redis.*)", target);
        }
        return new SwitchableRedisConnectionFactory(target, properties.redisCommandTimeoutOrDefault(),
                properties.redisConnectTimeoutOrDefault());
    }

    @Bean
    JsonHttp homeServerHttp(ConnectionsProperties properties) {
        return new JsonHttp(properties.httpTimeoutOrDefault());
    }

    @Bean
    ValhallaClient valhallaClient(JsonHttp homeServerHttp) {
        return new ValhallaClient(homeServerHttp);
    }

    @Bean
    IngestionApiClient ingestionApiClient(JsonHttp homeServerHttp) {
        return new IngestionApiClient(homeServerHttp);
    }
}
