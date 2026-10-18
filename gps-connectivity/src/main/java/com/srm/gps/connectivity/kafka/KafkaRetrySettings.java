package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What a consumer does when handling a message fails.
 *
 *  - PostgreSQL or Kafka is down: keep retrying (1 s, 2 s, 4 s ... at most every 60 s) until it is back.
 *    The message is not skipped, so nothing is lost - it simply waits in Kafka.
 *  - The message itself is broken (not valid JSON, breaks a database rule): retrying cannot help,
 *    so it is logged and skipped at once instead of blocking every message behind it.
 */
@Configuration
@OnlyInKafkaFlow
public class KafkaRetrySettings {

    @Bean
    public DefaultErrorHandler kafkaConsumerErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(60_000);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(backOff);
        errorHandler.addNotRetryableExceptions(
                GpsMessageJson.InvalidGpsMessageException.class,
                DataIntegrityViolationException.class);
        return errorHandler;
    }
}
