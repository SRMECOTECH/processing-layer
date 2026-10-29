package com.srm.gps.streams.errors;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.streams.GpsStreamsTopologies;
import com.srm.gps.streams.StoreNames;
import com.srm.gps.streams.StreamsObserver;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.analytics.VehicleSnapshot;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.ErrorHandlerContext;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.ProcessingExceptionHandler;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kafka Streams never skips a record: it parks what it cannot handle and retries what is only unreachable. */
class DeadLetterTopologyTest {

    private static final String IMEI = "0861000000000001";

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;

    @AfterEach
    void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void aRecordTheAnalyticsCannotReadIsParkedWithWhereItCameFromAndTheNextOneIsProcessed() {
        driver = new TopologyTestDriver(GpsStreamsTopologies.analytics(TopologySettings.defaults(), StreamsObserver.NONE),
                properties("analytics-dlq-test"));
        TestInputTopic<String, String> positions = driver.createInputTopic(KafkaTopicNames.PROCESSED_POSITIONS,
                Serdes.String().serializer(), Serdes.String().serializer());
        TestOutputTopic<byte[], byte[]> deadLetters = driver.createOutputTopic(KafkaTopicNames.DEAD_LETTERS,
                new ByteArrayDeserializer(), new ByteArrayDeserializer());

        positions.pipeInput(IMEI, "this is not a position");
        positions.pipeInput(IMEI, GpsMessageJson.toJson(new ProcessedGpsPosition(IMEI, Instant.parse("2026-10-25T10:00:00Z"),
                22.8, 86.2, 20, MovementStatus.MOVING, 100, 60, false, Instant.now(), null, null, 100.0, DistanceSource.GPS, null)));

        TestRecord<byte[], byte[]> parked = deadLetters.readRecord();
        assertEquals("this is not a position", new String(parked.value(), StandardCharsets.UTF_8), "kept exactly as it arrived");
        assertEquals(KafkaTopicNames.PROCESSED_POSITIONS, header(parked.headers(), "__streams.errors.topic"));
        assertEquals("0", header(parked.headers(), "__streams.errors.offset"));
        VehicleSnapshot snapshot = driver.<String, VehicleSnapshot>getKeyValueStore(StoreNames.VEHICLE_TABLE).get(IMEI);
        assertEquals(1, snapshot.readings(), "the stream moved on to the next record");
    }

    @Test
    void anUnreachableDatabaseStopsTheTaskInsteadOfParkingOrSkippingTheFrame() {
        driver = new TopologyTestDriver(GpsStreamsTopologies.normalizer(TopologySettings.defaults(),
                imei -> {
                    throw new IllegalStateException("Connection refused: PostgreSQL");
                }, StreamsObserver.NONE), properties("normalizer-dlq-test"));
        TestInputTopic<String, String> raw = driver.createInputTopic(KafkaTopicNames.RAW_DEVICE_FRAMES,
                Serdes.String().serializer(), Serdes.String().serializer());
        TestOutputTopic<byte[], byte[]> deadLetters = driver.createOutputTopic(KafkaTopicNames.DEAD_LETTERS,
                new ByteArrayDeserializer(), new ByteArrayDeserializer());
        TestOutputTopic<String, String> processed = driver.createOutputTopic(KafkaTopicNames.PROCESSED_POSITIONS,
                Serdes.String().deserializer(), Serdes.String().deserializer());

        String frame = GpsMessageJson.toJson(new RawGpsFrame(IMEI, Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS),
                22.8, 86.2, 10));
        StreamsException stopped = assertThrows(StreamsException.class, () -> raw.pipeInput(IMEI, frame));

        assertTrue(StateUnavailableException.isCause(stopped), "failed for a retry from the last commit");
        assertTrue(deadLetters.isEmpty(), "not parked: the frame is fine");
        assertTrue(processed.isEmpty());
    }

    @Test
    void theHandlerParksTheSourceRecordForAnythingElse() {
        DeadLetterProcessingExceptionHandler handler = new DeadLetterProcessingExceptionHandler();
        handler.configure(Map.of(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, KafkaTopicNames.DEAD_LETTERS));

        ProcessingExceptionHandler.Response response = handler.handleError(context(), new Record<>("k", "v", 0L),
                new NullPointerException("a bug"));

        assertEquals(ProcessingExceptionHandler.Result.RESUME, response.result());
        ProducerRecord<byte[], byte[]> parked = response.deadLetterQueueRecords().get(0);
        assertEquals(KafkaTopicNames.DEAD_LETTERS, parked.topic());
        assertEquals("raw-bytes", new String(parked.value(), StandardCharsets.UTF_8));
        assertEquals("java.lang.NullPointerException", header(parked.headers(), "__streams.errors.exception"));
        assertEquals("normalize-frame", header(parked.headers(), "__streams.errors.processor"));
    }

    @Test
    void withoutADeadLetterTopicTheHandlerStopsRatherThanSkip() {
        DeadLetterProcessingExceptionHandler handler = new DeadLetterProcessingExceptionHandler();
        handler.configure(Map.of());

        assertEquals(ProcessingExceptionHandler.Result.FAIL,
                handler.handleError(context(), new Record<>("k", "v", 0L), new NullPointerException("a bug")).result());
    }

    private Properties properties(String applicationId) {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        properties.put(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, KafkaTopicNames.DEAD_LETTERS);
        properties.put(StreamsConfig.DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndContinueExceptionHandler.class);
        properties.put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG, DeadLetterProcessingExceptionHandler.class);
        return properties;
    }

    private static String header(Headers headers, String name) {
        assertNotNull(headers.lastHeader(name), name);
        return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static ErrorHandlerContext context() {
        return new ErrorHandlerContext() {
            public String topic() { return KafkaTopicNames.RAW_DEVICE_FRAMES; }
            public int partition() { return 1; }
            public long offset() { return 42; }
            public Headers headers() { return new RecordHeaders(); }
            public String processorNodeId() { return "normalize-frame"; }
            public TaskId taskId() { return new TaskId(0, 1); }
            public long timestamp() { return 0; }
            public byte[] sourceRawKey() { return IMEI.getBytes(StandardCharsets.UTF_8); }
            public byte[] sourceRawValue() { return "raw-bytes".getBytes(StandardCharsets.UTF_8); }
        };
    }
}
