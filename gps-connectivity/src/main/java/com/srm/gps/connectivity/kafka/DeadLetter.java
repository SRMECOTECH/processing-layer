package com.srm.gps.connectivity.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * One record read from gps.dead-letters, with where it came from and why it was parked. Two writers, two header
 * styles, one shape:
 *
 *   listeners      (Spring's DeadLetterPublishingRecoverer)  kafka_dlt-original-topic, -partition, -offset, -consumer-group,
 *                                                           kafka_dlt-exception-fqcn, -message, -stacktrace
 *   Kafka Streams  (errors.dead.letter.queue.topic.name)     __streams.errors.topic, .partition, .offset,
 *                                                           .exception, .message, .stacktrace
 *
 * @param source           "listener" or "kafka-streams"
 * @param consumerGroup    the listener's group; for Kafka Streams the application id is not in the headers (null)
 * @param deadLetterTopic  where it was read from, with partition and offset: the archive's idempotency key
 */
public record DeadLetter(
        String source,
        String originalTopic,
        Integer originalPartition,
        Long originalOffset,
        String consumerGroup,
        String key,
        String value,
        String exceptionClass,
        String exceptionMessage,
        String stacktrace,
        Instant parkedAt,
        String deadLetterTopic,
        int deadLetterPartition,
        long deadLetterOffset) {

    public static final String LISTENER = "listener";
    public static final String KAFKA_STREAMS = "kafka-streams";
    private static final String STREAMS_PREFIX = "__streams.errors.";

    public static DeadLetter from(ConsumerRecord<String, String> record) {
        Headers headers = record.headers();
        boolean streams = headers.lastHeader(STREAMS_PREFIX + "topic") != null;
        Instant parkedAt = Instant.ofEpochMilli(record.timestamp());
        if (streams) {
            return new DeadLetter(KAFKA_STREAMS,
                    text(headers, STREAMS_PREFIX + "topic"),
                    integer(text(headers, STREAMS_PREFIX + "partition")),
                    number(text(headers, STREAMS_PREFIX + "offset")),
                    null,
                    record.key(), record.value(),
                    text(headers, STREAMS_PREFIX + "exception"),
                    text(headers, STREAMS_PREFIX + "message"),
                    text(headers, STREAMS_PREFIX + "stacktrace"),
                    parkedAt, record.topic(), record.partition(), record.offset());
        }
        return new DeadLetter(LISTENER,
                text(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                binaryInt(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                binaryLong(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                text(headers, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
                record.key(), record.value(),
                text(headers, KafkaHeaders.DLT_EXCEPTION_FQCN),
                text(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                text(headers, KafkaHeaders.DLT_EXCEPTION_STACKTRACE),
                parkedAt, record.topic(), record.partition(), record.offset());
    }

    private static String text(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Spring writes the original partition and offset as raw big-endian numbers, not text. */
    private static Integer binaryInt(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null || header.value().length != Integer.BYTES ? null
                : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long binaryLong(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null || header.value().length != Long.BYTES ? null
                : ByteBuffer.wrap(header.value()).getLong();
    }

    private static Integer integer(String text) {
        try {
            return text == null ? null : Integer.valueOf(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Long number(String text) {
        try {
            return text == null ? null : Long.valueOf(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
