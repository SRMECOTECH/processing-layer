package com.srm.gps.application.admin;

import com.srm.gps.application.processing.DatabaseBackedPreviousReadingStore;
import com.srm.gps.application.simulator.GpsDeviceSimulatorService;
import com.srm.gps.application.simulator.GpsDeviceSimulatorSettings;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.connectivity.kafka.KafkaBacklogSkipper;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.connectivity.redis.RedisStateStore;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * "Empty DB" for demo practice: brings the platform back to a clean start without restarting anything.
 *
 *   1. stop the simulated fleet;
 *   2. pause every Kafka consumer and move each one to the end of its topic (a paused consumer still polls,
 *      so the seek is applied and its prefetched records are dropped);
 *   3. delete every record in the platform's topics (deleteRecords up to the end offset);
 *   4. empty the telemetry tables - master data (tenant, vehicle, device, device_assignment) stays;
 *   5. delete the gps:* Redis keys except configuration (gps:config:*, the speed rules);
 *   6. forget the in-memory previous readings and start the load counters from 0;
 *   7. resume the consumers, and restart the fleet if asked.
 * Not for production: it is switched off with gps.admin.reset-enabled=false.
 */
@Service
public class DemoResetService {

    private static final Logger LOG = LoggerFactory.getLogger(DemoResetService.class);
    private static final List<String> TOPICS = List.of(KafkaTopicNames.RAW_DEVICE_FRAMES, KafkaTopicNames.PROCESSED_POSITIONS,
            KafkaTopicNames.REJECTED_FRAMES, KafkaTopicNames.VEHICLE_EVENTS);
    private static final List<String> TELEMETRY_TABLES = List.of("vehicle_event", "gps_trip_late_reading", "gps_trip",
            "gps_stop", "vehicle_motion_state", "gps_position");

    private final GpsDeviceSimulatorService simulator;
    private final GpsDeviceSimulatorSettings simulatorSettings;
    private final ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistry;
    private final ObjectProvider<KafkaBacklogSkipper> backlogSkipper;
    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    private final JdbcTemplate jdbc;
    private final RedisStateStore redis;
    private final DatabaseBackedPreviousReadingStore previousReadings;
    private final PipelineMetrics metrics;

    public DemoResetService(GpsDeviceSimulatorService simulator, GpsDeviceSimulatorSettings simulatorSettings,
                            ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistry,
                            ObjectProvider<KafkaBacklogSkipper> backlogSkipper, ObjectProvider<KafkaAdmin> kafkaAdmin,
                            JdbcTemplate jdbc, RedisStateStore redis, DatabaseBackedPreviousReadingStore previousReadings,
                            PipelineMetrics metrics) {
        this.simulator = simulator;
        this.simulatorSettings = simulatorSettings;
        this.listenerRegistry = listenerRegistry;
        this.backlogSkipper = backlogSkipper;
        this.kafkaAdmin = kafkaAdmin;
        this.jdbc = jdbc;
        this.redis = redis;
        this.previousReadings = previousReadings;
        this.metrics = metrics;
    }

    public record ResetReport(Instant at, long tookMillis, boolean fleetWasRunning, boolean fleetRestarted,
                              int listenersSkippedToEnd, Map<String, Long> kafkaRecordsDeleted, List<String> tablesEmptied,
                              long redisKeysDeleted, List<String> notes) {
    }

    public synchronized ResetReport reset(boolean restartFleet) {
        long start = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        boolean wasRunning = simulator.currentStatus().running();

        simulator.stopFleet();
        if (!waitFor(() -> !simulator.currentStatus().running(), Duration.ofSeconds(10))) {
            notes.add("The fleet took longer than 10 s to stop");
        }

        KafkaListenerEndpointRegistry registry = listenerRegistry.getIfAvailable();
        List<MessageListenerContainer> containers = registry == null ? List.of() : List.copyOf(registry.getListenerContainers());
        containers.forEach(MessageListenerContainer::pause);
        if (!containers.isEmpty() && !waitFor(() -> containers.stream().allMatch(MessageListenerContainer::isPauseRequested), Duration.ofSeconds(5))) {
            notes.add("Not every consumer confirmed the pause");
        }

        int skipped = 0;
        KafkaBacklogSkipper skipper = backlogSkipper.getIfAvailable();
        if (skipper != null) {
            skipped = skipper.skipToEnd();
            sleep(Duration.ofSeconds(6));   // longer than the consumers' poll timeout: every seek has been applied
        }

        Map<String, Long> deleted = registry == null ? Map.of() : purgeTopics(notes);

        List<String> emptied = new ArrayList<>();
        for (String table : TELEMETRY_TABLES) {
            try {
                jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY CASCADE");
                emptied.add(table);
            } catch (RuntimeException problem) {
                notes.add("Could not empty " + table + ": " + problem.getMessage());
            }
        }

        long redisKeys = 0;
        try {
            redisKeys = redis.deleteTelemetry();
        } catch (RuntimeException problem) {
            notes.add("Redis not emptied: " + problem.getMessage());
        }
        previousReadings.forgetAll();
        metrics.reset();

        containers.forEach(MessageListenerContainer::resume);

        boolean restarted = false;
        if (restartFleet) {
            simulator.startFleet(simulatorSettings.vehicleCount(), simulatorSettings.pingsPerVehicle());
            restarted = true;
        }

        ResetReport report = new ResetReport(Instant.now(), System.currentTimeMillis() - start, wasRunning, restarted, skipped,
                deleted, emptied, redisKeys, notes);
        LOG.warn("DEMO RESET done: {}", report);
        return report;
    }

    /** Deletes every record of the platform's topics; returns how many per topic. */
    private Map<String, Long> purgeTopics(List<String> notes) {
        Map<String, Long> deletedPerTopic = new LinkedHashMap<>();
        KafkaAdmin admin = kafkaAdmin.getIfAvailable();
        if (admin == null) {
            return deletedPerTopic;
        }
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            Map<String, TopicDescription> topics = client.describeTopics(TOPICS).allTopicNames().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latestRequest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliestRequest = new HashMap<>();
            topics.values().forEach(topic -> topic.partitions().forEach(p -> {
                TopicPartition tp = new TopicPartition(topic.name(), p.partition());
                latestRequest.put(tp, OffsetSpec.latest());
                earliestRequest.put(tp, OffsetSpec.earliest());
            }));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = client.listOffsets(latestRequest).all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliest = client.listOffsets(earliestRequest).all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, RecordsToDelete> toDelete = new HashMap<>();
            latest.forEach((tp, info) -> {
                long count = info.offset() - earliest.get(tp).offset();
                deletedPerTopic.merge(tp.topic(), Math.max(0, count), Long::sum);
                toDelete.put(tp, RecordsToDelete.beforeOffset(info.offset()));
            });
            client.deleteRecords(toDelete).all().get(30, TimeUnit.SECONDS);
        } catch (Exception problem) {
            notes.add("Kafka topics not emptied: " + problem.getMessage());
        }
        return deletedPerTopic;
    }

    private static boolean waitFor(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Duration.ofMillis(200));
        }
        return condition.getAsBoolean();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
