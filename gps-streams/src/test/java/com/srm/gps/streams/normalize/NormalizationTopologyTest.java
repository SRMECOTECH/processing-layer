package com.srm.gps.streams.normalize;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.processing.step.PreviousReading;
import com.srm.gps.streams.GpsStreamsTopologies;
import com.srm.gps.streams.StoreNames;
import com.srm.gps.streams.StreamsObserver;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.runtime.BoundedMemoryRocksDbConfig;
import com.srm.gps.streams.serde.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The normalizer topology, end to end, without a broker (TopologyTestDriver runs the real stores). */
class NormalizationTopologyTest {

    private static final String IMEI = "0861000000000001";
    private static final Instant ONE_HOUR_AGO = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);

    @TempDir
    Path stateDir;

    private final List<String> observed = new ArrayList<>();
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> raw;
    private TestOutputTopic<String, ProcessedGpsPosition> processed;
    private TestOutputTopic<String, RejectedGpsFrame> rejected;

    @AfterEach
    void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void anAcceptedFrameIsPublishedAsAPositionCarryingItsEventTime() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));

        TestRecord<String, ProcessedGpsPosition> record = processed.readRecord();
        assertEquals(IMEI, record.key());
        assertEquals(ONE_HOUR_AGO, record.value().recordedAt());
        assertEquals(DistanceSource.FIRST_READING, record.value().distanceSource());
        assertEquals(ONE_HOUR_AGO, record.getRecordTime(), "the output is stamped with recordedAt, not the arrival time");
        assertTrue(rejected.isEmpty());
        assertEquals(List.of(IMEI + " accepted"), observed);
    }

    @Test
    void theNextReadingIsMeasuredFromThePreviousOneInTheStateStore() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));
        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO.plusSeconds(30), 22.7982, 86.2095, 20));

        ProcessedGpsPosition second = processed.readValuesToList().get(1);
        assertTrue(second.distanceFromPreviousMeters() > 100);
        assertEquals(30, second.secondsSincePreviousReading());
        PreviousReading remembered = driver.<String, PreviousReading>getKeyValueStore(StoreNames.PREVIOUS_READINGS).get(IMEI);
        assertEquals(ONE_HOUR_AGO.plusSeconds(30), remembered.recordedAt());
        assertEquals(second.totalDistanceMeters(), remembered.totalDistanceMeters());
    }

    @Test
    void anInvalidFrameIsRejectedWithThePlainEnglishReason() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 95.0, 86.2095, 10));
        raw.pipeInput("whatever", "this is not json");

        List<TestRecord<String, RejectedGpsFrame>> rejections = rejected.readRecordsToList();
        assertEquals("Latitude 95.0 is outside -90..90", rejections.get(0).value().rejectionReason());
        assertEquals(IMEI, rejections.get(0).key());
        assertEquals("Frame is not valid JSON", rejections.get(1).value().rejectionReason());
        assertNull(rejections.get(1).key(), "no IMEI could be read, so no key");
        assertTrue(processed.isEmpty());
    }

    @Test
    void aFrameKeyedByAnythingButItsImeiIsRejected() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput("0861000000000999", frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));
        raw.pipeInput((String) null, frame(ONE_HOUR_AGO.plusSeconds(10), 22.7972, 86.2095, 0));

        List<RejectedGpsFrame> rejections = rejected.readValuesToList();
        assertTrue(rejections.get(0).rejectionReason().startsWith("Record key 0861000000000999 is not the frame's IMEI"));
        assertTrue(rejections.get(1).rejectionReason().startsWith("Record has no key"));
        assertTrue(processed.isEmpty());
    }

    @Test
    void aRepeatOfTheNewestReadingIsADuplicate() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);
        String reading = frame(ONE_HOUR_AGO, 22.7972, 86.2095, 10);

        raw.pipeInput(IMEI, reading);
        raw.pipeInput(IMEI, reading);

        assertEquals(1, processed.readValuesToList().size());
        assertTrue(rejected.readValue().rejectionReason().startsWith("Duplicate reading"));
    }

    @Test
    void aReSentOlderReadingIsCaughtAsDuplicateNotAcceptedAsLate() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 22.7972, 86.2095, 10));
        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO.plusSeconds(60), 22.7982, 86.2095, 10));
        // the device reconnects and uploads its buffer again: the first reading is older than the newest one
        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 22.7972, 86.2095, 10));

        assertEquals(2, processed.readValuesToList().size());
        assertTrue(rejected.readValue().rejectionReason().startsWith("Duplicate reading"),
                "the listener would accept it as a late reading; the seen-reading store knows it was already sent");
    }

    @Test
    void aLateReadingIsAcceptedFlaggedAndNeverBecomesTheNewest() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO.plusSeconds(120), 22.7972, 86.2095, 0));
        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO.plusSeconds(60), 22.7900, 86.2095, 0));
        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO.plusSeconds(180), 22.7972, 86.2095, 0));

        List<ProcessedGpsPosition> positions = processed.readValuesToList();
        ProcessedGpsPosition late = positions.get(1);
        assertTrue(late.arrivedOutOfOrder());
        assertEquals(0, late.distanceFromPreviousMeters());
        assertEquals(DistanceSource.OUT_OF_ORDER, late.distanceSource());
        assertEquals(60, positions.get(2).secondsSincePreviousReading(), "measured from the newest reading, not the late one");
    }

    @Test
    void aReadingFarOlderThanItsDevicesNewestIsRejectedAsStale() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);
        Instant newest = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);

        raw.pipeInput(IMEI, frame(newest, 22.7972, 86.2095, 0));
        raw.pipeInput(IMEI, frame(newest.minus(Duration.ofHours(25)), 22.7972, 86.2095, 0));

        assertEquals(1, processed.readValuesToList().size());
        assertTrue(rejected.readValue().rejectionReason().startsWith("Stale reading"));
    }

    @Test
    void anUnknownDeviceStartsFromTheFallbackAndKeepsItsRunningTotal() {
        PreviousReading stored = new PreviousReading(ONE_HOUR_AGO, 22.7972, 86.2095, 1_000, null);
        start(TopologySettings.defaults(), imei -> IMEI.equals(imei) ? Optional.of(stored) : Optional.empty());

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO.plusSeconds(30), 22.7982, 86.2095, 20));

        ProcessedGpsPosition position = processed.readValue();
        assertEquals(DistanceSource.GPS, position.distanceSource());
        assertEquals(1_000 + position.distanceFromPreviousMeters(), position.totalDistanceMeters(), 0.11);
    }

    @Test
    void inShadowModeTheResultsGoToTheShadowTopics() {
        start(TopologySettings.defaults().withNormalization(StreamsNormalization.SHADOW), PreviousReadingFallback.NONE);
        TestOutputTopic<String, ProcessedGpsPosition> shadowProcessed = driver.createOutputTopic(
                KafkaTopicNames.SHADOW_PROCESSED_POSITIONS, Serdes.String().deserializer(), JsonSerde.of(ProcessedGpsPosition.class).deserializer());
        TestOutputTopic<String, RejectedGpsFrame> shadowRejected = driver.createOutputTopic(
                KafkaTopicNames.SHADOW_REJECTED_FRAMES, Serdes.String().deserializer(), JsonSerde.of(RejectedGpsFrame.class).deserializer());

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));
        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 95.0, 86.2095, 0));

        assertEquals(1, shadowProcessed.readValuesToList().size());
        assertEquals(1, shadowRejected.readValuesToList().size());
        assertTrue(processed.isEmpty());
        assertTrue(rejected.isEmpty());
    }

    @Test
    void aProcessedPositionIsWrittenExactlyAsTheListenerWritesIt() {
        start(TopologySettings.defaults(), PreviousReadingFallback.NONE);

        raw.pipeInput(IMEI, frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));

        ProcessedGpsPosition position = processed.readValue();
        String asTheListenerWritesIt = GpsMessageJson.toJson(position);
        assertEquals(position, GpsMessageJson.fromJson(asTheListenerWritesIt, ProcessedGpsPosition.class));
        assertFalse(asTheListenerWritesIt.isBlank());
    }

    private void start(TopologySettings settings, PreviousReadingFallback fallback) {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "normalizer-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        properties.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        properties.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, BoundedMemoryRocksDbConfig.class);
        StreamsObserver observer = new StreamsObserver() {
            @Override
            public void frameNormalized(String imei, int partition, boolean accepted) {
                observed.add(imei + (accepted ? " accepted" : " rejected"));
            }
        };
        driver = new TopologyTestDriver(GpsStreamsTopologies.normalizer(settings, fallback, observer), properties);
        raw = driver.createInputTopic(KafkaTopicNames.RAW_DEVICE_FRAMES, Serdes.String().serializer(), Serdes.String().serializer());
        processed = driver.createOutputTopic(KafkaTopicNames.PROCESSED_POSITIONS, Serdes.String().deserializer(),
                JsonSerde.of(ProcessedGpsPosition.class).deserializer());
        rejected = driver.createOutputTopic(KafkaTopicNames.REJECTED_FRAMES, Serdes.String().deserializer(),
                JsonSerde.of(RejectedGpsFrame.class).deserializer());
    }

    private static String frame(Instant recordedAt, double latitude, double longitude, int speedKmph) {
        return GpsMessageJson.toJson(new RawGpsFrame(IMEI, recordedAt, latitude, longitude, speedKmph));
    }
}
