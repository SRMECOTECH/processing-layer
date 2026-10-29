package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.json.GpsMessageJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.support.KafkaHeaders;

import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which failures wait and which are parked, and how a parked record is read back from either writer. */
class DeadLetterHandlingTest {

    @Test
    void anUnreachableDatabaseRedisOrKafkaIsWaitedFor() {
        assertTrue(FailureClassifier.isInfrastructure(new CannotGetJdbcConnectionException("PostgreSQL is down")));
        assertTrue(FailureClassifier.isInfrastructure(new RedisConnectionFailureException("Redis is down")));
        assertTrue(FailureClassifier.isInfrastructure(new KafkaException("send failed", new TimeoutException("no ack"))));
        assertTrue(FailureClassifier.isInfrastructure(new IllegalStateException("wrapped", new ConnectException("refused"))));
    }

    @Test
    void aBrokenMessageARuleItBreaksOrABugIsParked() {
        assertFalse(FailureClassifier.isInfrastructure(new GpsMessageJson.InvalidGpsMessageException("not JSON", null)));
        assertFalse(FailureClassifier.isInfrastructure(new DataIntegrityViolationException("distance_m >= 0")));
        assertFalse(FailureClassifier.isInfrastructure(new NullPointerException("a bug")));
    }

    @Test
    void aListenersDeadLetterIsReadWithItsOriginAndError() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "gps.processed-positions".getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(2).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(1534).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "gps-position-storage-service".getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN, "org.springframework.dao.DataIntegrityViolationException".getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "violates check".getBytes(StandardCharsets.UTF_8));

        DeadLetter deadLetter = DeadLetter.from(record(headers));

        assertEquals(DeadLetter.LISTENER, deadLetter.source());
        assertEquals("gps.processed-positions", deadLetter.originalTopic());
        assertEquals(2, deadLetter.originalPartition());
        assertEquals(1534L, deadLetter.originalOffset());
        assertEquals("gps-position-storage-service", deadLetter.consumerGroup());
        assertEquals("violates check", deadLetter.exceptionMessage());
        assertEquals("{\"imei\":\"1\"}", deadLetter.value());
        assertEquals(7L, deadLetter.deadLetterOffset());
    }

    @Test
    void aKafkaStreamsDeadLetterIsReadTheSameWay() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("__streams.errors.topic", "gps.raw-device-frames".getBytes(StandardCharsets.UTF_8));
        headers.add("__streams.errors.partition", "1".getBytes(StandardCharsets.UTF_8));
        headers.add("__streams.errors.offset", "99".getBytes(StandardCharsets.UTF_8));
        headers.add("__streams.errors.exception", "java.lang.NullPointerException".getBytes(StandardCharsets.UTF_8));

        DeadLetter deadLetter = DeadLetter.from(record(headers));

        assertEquals(DeadLetter.KAFKA_STREAMS, deadLetter.source());
        assertEquals("gps.raw-device-frames", deadLetter.originalTopic());
        assertEquals(1, deadLetter.originalPartition());
        assertEquals(99L, deadLetter.originalOffset());
        assertNull(deadLetter.consumerGroup());
        assertEquals("java.lang.NullPointerException", deadLetter.exceptionClass());
    }

    private static ConsumerRecord<String, String> record(RecordHeaders headers) {
        return new ConsumerRecord<>("gps.dead-letters", 0, 7, 1_700_000_000_000L, TimestampType.CREATE_TIME, -1, -1,
                "0861000000000001", "{\"imei\":\"1\"}", headers, Optional.empty());
    }
}
