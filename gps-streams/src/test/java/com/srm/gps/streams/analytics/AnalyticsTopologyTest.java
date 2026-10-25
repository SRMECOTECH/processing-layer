package com.srm.gps.streams.analytics;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.streams.GpsStreamsTopologies;
import com.srm.gps.streams.StoreNames;
import com.srm.gps.streams.StreamsObserver;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.serde.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The analytics topology on event time: KTable, windows with grace, and the shadow parity join. */
class AnalyticsTopologyTest {

    private static final String IMEI = "0861000000000001";
    private static final Instant TEN = Instant.parse("2026-10-25T10:00:00Z");

    @TempDir
    Path stateDir;

    private TopologyTestDriver driver;
    private TestInputTopic<String, ProcessedGpsPosition> positions;
    private TestOutputTopic<String, VehicleActivity> activity;

    @AfterEach
    void closeDriver() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void theVehicleTableKeepsTheNewestPositionByEventTimeAndCountsLateOnes() {
        start(StreamsNormalization.OWNER);

        positions.pipeInput(IMEI, position(TEN.plusSeconds(120), 30, MovementStatus.MOVING, 400, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 10, MovementStatus.MOVING, 0, true));

        VehicleSnapshot snapshot = vehicleTable().get(IMEI);
        assertEquals(TEN.plusSeconds(120), snapshot.recordedAt(), "a late reading does not replace a newer one");
        assertEquals(30, snapshot.speedKmph());
        assertEquals(2, snapshot.readings());
        assertEquals(1, snapshot.outOfOrderReadings());
        assertEquals(TEN.plusSeconds(60), snapshot.firstRecordedAt());
    }

    @Test
    void theStatusSinceMovesOnlyWhenTheMovementStatusChanges() {
        start(StreamsNormalization.OWNER);

        positions.pipeInput(IMEI, position(TEN, 0, MovementStatus.STOPPED, 0, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 20, MovementStatus.MOVING, 300, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(120), 25, MovementStatus.MOVING, 400, false));

        VehicleSnapshot snapshot = vehicleTable().get(IMEI);
        assertEquals(MovementStatus.MOVING, snapshot.movementStatus());
        assertEquals(TEN.plusSeconds(60), snapshot.statusSince());
    }

    @Test
    void readingsAreCountedInTheirOwnFiveMinuteEventTimeWindow() {
        start(StreamsNormalization.OWNER);

        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(180), 20, MovementStatus.MOVING, 300, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(360), 0, MovementStatus.STOPPED, 0, false));

        List<KeyValue<Windowed<String>, ActivityWindow>> windows = windows();
        assertEquals(2, windows.size());
        ActivityWindow first = windows.get(0).value;
        assertEquals(TEN, windows.get(0).key.window().startTime());
        assertEquals(2, first.readings());
        assertEquals(800, first.distanceMeters());
        assertEquals(40, first.maxSpeedKmph());
        assertEquals(30, first.averageSpeedKmph());
        assertEquals(1, windows.get(1).value.readings());
    }

    @Test
    void aWindowIsPublishedOnceOnlyAfterItsGracePeriodHasPassed() {
        start(StreamsNormalization.OWNER);

        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(180), 20, MovementStatus.MOVING, 300, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(400), 20, MovementStatus.MOVING, 300, false));
        assertTrue(activity.isEmpty(), "10:06:40 is inside the 2-minute grace of the 10:00-10:05 window");

        // a reading 1 min after its window ended still counts: it is within the grace period
        positions.pipeInput(IMEI, position(TEN.plusSeconds(240), 10, MovementStatus.MOVING, 100, true));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(430), 20, MovementStatus.MOVING, 300, false));

        VehicleActivity closed = activity.readValue();
        assertEquals(TEN, closed.windowStart());
        assertEquals(TEN.plusSeconds(300), closed.windowEnd());
        assertEquals(3, closed.readings());
        assertEquals(1, closed.outOfOrderReadings());
        assertTrue(activity.isEmpty(), "one final record per window, no updates");
    }

    @Test
    void aReadingLaterThanTheGraceIsNotCounted() {
        start(StreamsNormalization.OWNER);

        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(480), 20, MovementStatus.MOVING, 300, false));
        positions.pipeInput(IMEI, position(TEN.plusSeconds(120), 20, MovementStatus.MOVING, 0, true));

        assertEquals(1, windows().get(0).value.readings(), "the 10:00 window closed at 10:07; a 10:02 reading at 10:08 is dropped");
    }

    @Test
    void inShadowModeTheSameReadingFromBothSidesIsAMatch() {
        start(StreamsNormalization.SHADOW);
        TestInputTopic<String, ProcessedGpsPosition> shadow = shadowInput();
        ProcessedGpsPosition reading = position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false);

        positions.pipeInput(IMEI, reading);
        shadow.pipeInput(IMEI, reading);

        ParityTally tally = parity().get(IMEI);
        assertEquals(1, tally.matched());
        assertEquals(0, tally.mismatched());
    }

    @Test
    void inShadowModeADifferentResultIsAMismatchNamingTheField() {
        start(StreamsNormalization.SHADOW);
        TestInputTopic<String, ProcessedGpsPosition> shadow = shadowInput();

        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false));
        shadow.pipeInput(IMEI, position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 480, false));

        ParityTally tally = parity().get(IMEI);
        assertEquals(1, tally.mismatched());
        assertNotNull(tally.lastDifference());
        assertTrue(tally.lastDifference().differences().get(0).startsWith("distanceFromPreviousMeters"));
    }

    @Test
    void inShadowModeAReadingOnlyOneSideProducedIsReportedOnceItsWindowCloses() {
        start(StreamsNormalization.SHADOW);
        TestInputTopic<String, ProcessedGpsPosition> shadow = shadowInput();

        positions.pipeInput(IMEI, position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false));
        // stream time moves past 10:01 + 2 min grace on both sides
        positions.pipeInput(IMEI, position(TEN.plusSeconds(600), 40, MovementStatus.MOVING, 500, false));
        shadow.pipeInput(IMEI, position(TEN.plusSeconds(600), 40, MovementStatus.MOVING, 500, false));

        ParityTally tally = parity().get(IMEI);
        assertEquals(1, tally.onlyListener());
        assertEquals(1, tally.matched());
    }

    @Test
    void theShadowsFirstReadingIsWarmUpNotAMismatch() {
        start(StreamsNormalization.SHADOW);
        TestInputTopic<String, ProcessedGpsPosition> shadow = shadowInput();
        ProcessedGpsPosition listener = position(TEN.plusSeconds(60), 40, MovementStatus.MOVING, 500, false);

        positions.pipeInput(IMEI, listener);
        shadow.pipeInput(IMEI, new ProcessedGpsPosition(IMEI, listener.recordedAt(), listener.latitude(), listener.longitude(),
                40, MovementStatus.MOVING, 0, 0, false, Instant.now(), null, null, 0.0, DistanceSource.FIRST_READING, null));

        assertEquals(1, parity().get(IMEI).warmup());
    }

    private void start(StreamsNormalization mode) {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "analytics-test");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
        TopologySettings settings = TopologySettings.defaults().withNormalization(mode);
        driver = new TopologyTestDriver(GpsStreamsTopologies.analytics(settings, StreamsObserver.NONE), properties);
        positions = driver.createInputTopic(KafkaTopicNames.PROCESSED_POSITIONS, Serdes.String().serializer(),
                JsonSerde.of(ProcessedGpsPosition.class).serializer());
        activity = driver.createOutputTopic(KafkaTopicNames.VEHICLE_ACTIVITY, Serdes.String().deserializer(),
                JsonSerde.of(VehicleActivity.class).deserializer());
    }

    private TestInputTopic<String, ProcessedGpsPosition> shadowInput() {
        return driver.createInputTopic(KafkaTopicNames.SHADOW_PROCESSED_POSITIONS, Serdes.String().serializer(),
                JsonSerde.of(ProcessedGpsPosition.class).serializer());
    }

    private KeyValueStore<String, VehicleSnapshot> vehicleTable() {
        return driver.getKeyValueStore(StoreNames.VEHICLE_TABLE);
    }

    private KeyValueStore<String, ParityTally> parity() {
        return driver.getKeyValueStore(StoreNames.PARITY_TALLY);
    }

    private List<KeyValue<Windowed<String>, ActivityWindow>> windows() {
        WindowStore<String, ActivityWindow> store = driver.getWindowStore(StoreNames.ACTIVITY_WINDOWS);
        List<KeyValue<Windowed<String>, ActivityWindow>> windows = new ArrayList<>();
        try (KeyValueIterator<Windowed<String>, ActivityWindow> all = store.all()) {
            all.forEachRemaining(windows::add);
        }
        return windows;
    }

    private static ProcessedGpsPosition position(Instant recordedAt, int speedKmph, MovementStatus status, double distance, boolean late) {
        return new ProcessedGpsPosition(IMEI, recordedAt, 22.7972, 86.2095, speedKmph, status, distance, late ? 0 : 60, late,
                recordedAt.plusSeconds(1), null, null, 10_000.0, late ? DistanceSource.OUT_OF_ORDER : DistanceSource.GPS, null);
    }
}
