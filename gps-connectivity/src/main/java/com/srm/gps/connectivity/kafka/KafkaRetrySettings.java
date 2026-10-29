package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * What a consumer does when handling a message fails. No path ends in a skipped record:
 *
 *  - infrastructure is down (PostgreSQL, Redis, Kafka, network - see {@link FailureClassifier}): retry 1 s, 2 s, 4 s
 *    ... then every 60 s, FOREVER. The partition waits; the message waits safely in Kafka.
 *  - the message itself cannot be handled (not valid JSON, breaks a database rule): straight to gps.dead-letters.
 *  - anything else (most likely a bug): 3 tries, then gps.dead-letters.
 *
 * A dead letter is published and confirmed by Kafka BEFORE the offset moves on; if that publish fails, the record is
 * delivered again. Each dead letter carries the original topic, partition, offset, consumer group, timestamp and the
 * exception in its headers, and is archived in PostgreSQL by the dead-letter archive, from where it can be replayed.
 * The dead-letter archive's own records are never parked again: they are retried until they are stored.
 */
@Configuration
@OnlyInKafkaFlow
public class KafkaRetrySettings {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaRetrySettings.class);
    private static final int ATTEMPTS_BEFORE_PARKING = 3;

    @Bean
    public DefaultErrorHandler kafkaConsumerErrorHandler(KafkaTemplate<String, String> kafkaTemplate, PipelineMetrics metrics) {
        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate, (record, failure) -> {
            if (KafkaTopicNames.DEAD_LETTERS.equals(record.topic())) {
                throw new IllegalStateException("A dead letter is never parked again: it is retried until it is archived");
            }
            return new TopicPartition(KafkaTopicNames.DEAD_LETTERS, -1);   // -1: partition by the original key
        });
        toDeadLetters.setWaitForSendResultTimeout(Duration.ofSeconds(30));
        // the consumer group that gave up on the record (Spring only knows it from the wrapper exception, unwrapped below)
        toDeadLetters.addHeadersFunction((record, failure) -> {
            String group = KafkaUtils.getConsumerGroupId();
            return group == null ? new RecordHeaders()
                    : new RecordHeaders(new Header[] {new RecordHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, group.getBytes(StandardCharsets.UTF_8))});
        });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, failure) -> {
            // the dead letter names what went wrong, not Spring's "listener method threw" wrapper around it
            Exception cause = failure instanceof ListenerExecutionFailedException && failure.getCause() instanceof Exception inner
                    ? inner : failure;
            toDeadLetters.accept(record, cause);
            metrics.recordMessage(PipelineStage.DEAD_LETTER_PRODUCER, record.key() == null ? null : String.valueOf(record.key()), -1);
            LOG.error("Parked in {}: {}-{}@{} could not be handled ({}). It is archived and can be replayed.",
                    KafkaTopicNames.DEAD_LETTERS, record.topic(), record.partition(), record.offset(), cause.toString());
        }, waitForever());
        errorHandler.setBackOffFunction((record, failure) ->
                KafkaTopicNames.DEAD_LETTERS.equals(record.topic()) || FailureClassifier.isInfrastructure(failure)
                        ? waitForever() : new FixedBackOff(1_000, ATTEMPTS_BEFORE_PARKING - 1));
        errorHandler.addNotRetryableExceptions(
                GpsMessageJson.InvalidGpsMessageException.class,
                DataIntegrityViolationException.class);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    /** 1 s, 2 s, 4 s ... at most every 60 s, with no end. */
    private static BackOff waitForever() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(60_000);
        return backOff;
    }
}
