package com.srm.gps.application.engine;

import com.srm.gps.application.engine.EngineViews.ConsumerView;
import com.srm.gps.application.engine.EngineViews.DeviceLoadView;
import com.srm.gps.application.engine.EngineViews.EngineOverview;
import com.srm.gps.application.engine.EngineViews.StageView;
import com.srm.gps.application.flow.RawGpsFrameSender;
import com.srm.gps.application.simulator.GpsDeviceSimulatorService;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.ProcessingFlowNames;
import com.srm.gps.connectivity.database.repository.GpsPositionRepository;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector.ConsumerGroupView;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector.TopicView;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.connectivity.monitoring.StageSnapshot;
import com.srm.gps.connectivity.redis.LiveVehiclePositionStore;
import org.apache.kafka.common.utils.Utils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds the engine dashboard: the three producers, all topics, all consumers (with their load and lag),
 * PostgreSQL and Redis numbers, and the per-device (IMEI) view.
 */
@Service
public class EngineOverviewService {

    private static final List<String> TOPICS = List.of(
            KafkaTopicNames.RAW_DEVICE_FRAMES, KafkaTopicNames.PROCESSED_POSITIONS, KafkaTopicNames.REJECTED_FRAMES);
    private static final long STORED_COUNT_CACHE_MILLIS = 5_000;
    private static final int MAXIMUM_DEVICES_LISTED = 300;

    private final PipelineMetrics metrics;
    private final RawGpsFrameSender rawGpsFrameSender;
    private final GpsDeviceSimulatorService simulatorService;
    private final GpsPositionRepository gpsPositionRepository;
    private final LiveVehiclePositionStore liveVehiclePositionStore;
    private final ObjectProvider<KafkaTopologyInspector> kafkaTopologyInspector;

    private volatile long cachedStoredPositions;
    private volatile long storedPositionsCountedAtMillis;

    public EngineOverviewService(PipelineMetrics metrics,
                                 RawGpsFrameSender rawGpsFrameSender,
                                 GpsDeviceSimulatorService simulatorService,
                                 GpsPositionRepository gpsPositionRepository,
                                 LiveVehiclePositionStore liveVehiclePositionStore,
                                 ObjectProvider<KafkaTopologyInspector> kafkaTopologyInspector) {
        this.metrics = metrics;
        this.rawGpsFrameSender = rawGpsFrameSender;
        this.simulatorService = simulatorService;
        this.gpsPositionRepository = gpsPositionRepository;
        this.liveVehiclePositionStore = liveVehiclePositionStore;
        this.kafkaTopologyInspector = kafkaTopologyInspector;
    }

    public EngineOverview overview() {
        boolean kafkaFlow = ProcessingFlowNames.KAFKA.equals(rawGpsFrameSender.flowName());

        List<StageView> producers = new ArrayList<>();
        List<PipelineStage> consumerStages = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            if (stage.kind() == PipelineStage.Kind.PRODUCER) {
                producers.add(StageView.from(metrics.snapshot(stage)));
            } else if (stage.kind() == PipelineStage.Kind.CONSUMER) {
                consumerStages.add(stage);
            }
        }

        List<TopicView> topics = List.of();
        Map<String, ConsumerGroupView> groupsById = Map.of();
        String kafkaProblem = null;
        KafkaTopologyInspector inspector = kafkaTopologyInspector.getIfAvailable();
        if (kafkaFlow && inspector != null) {
            try {
                topics = inspector.describeTopics(TOPICS);
                Map<String, String> topicOfGroup = new LinkedHashMap<>();
                consumerStages.forEach(stage -> topicOfGroup.put(stage.consumerGroup(), stage.topic()));
                Map<String, ConsumerGroupView> found = new LinkedHashMap<>();
                inspector.describeGroups(topicOfGroup).forEach(group -> found.put(group.groupId(), group));
                groupsById = found;
            } catch (Exception problem) {
                kafkaProblem = "Kafka did not answer: " + problem.getMessage();
            }
        } else if (!kafkaFlow) {
            kafkaProblem = "Direct flow: Kafka is not used";
        }

        List<ConsumerView> consumers = new ArrayList<>();
        for (PipelineStage stage : consumerStages) {
            StageView load = StageView.from(metrics.snapshot(stage));
            ConsumerGroupView group = groupsById.get(stage.consumerGroup());
            consumers.add(group == null
                    ? new ConsumerView(load, kafkaFlow ? "UNKNOWN" : "NOT USED (direct flow)", List.of(), List.of(), 0)
                    : new ConsumerView(load, group.state(), group.members(), group.partitions(), group.totalLag()));
        }

        long vehiclesWithLivePosition = 0;
        String redisProblem = null;
        try {
            vehiclesWithLivePosition = liveVehiclePositionStore.countVehicles();
        } catch (RuntimeException problem) {
            redisProblem = "Redis did not answer: " + problem.getMessage();
        }

        return new EngineOverview(rawGpsFrameSender.flowName(), Instant.now(), simulatorService.currentStatus(),
                producers, consumers, StageView.from(metrics.snapshot(PipelineStage.DIRECT_PROCESSING)),
                topics, kafkaProblem, storedPositions(), vehiclesWithLivePosition, redisProblem);
    }

    /** Every device seen by any producer/consumer, optionally only IMEIs containing {@code imeiFilter}. */
    public List<DeviceLoadView> devices(String imeiFilter) {
        Map<PipelineStage, StageSnapshot> snapshots = allSnapshots();
        TreeSet<String> imeis = new TreeSet<>();
        snapshots.values().forEach(snapshot -> imeis.addAll(snapshot.messagesByImei().keySet()));

        List<DeviceLoadView> devices = new ArrayList<>();
        for (String imei : imeis) {
            if (imeiFilter != null && !imeiFilter.isBlank() && !imei.contains(imeiFilter.trim())) {
                continue;
            }
            devices.add(new DeviceLoadView(imei, partitionOf(imei), messagesByStage(imei, snapshots), Map.of()));
            if (devices.size() >= MAXIMUM_DEVICES_LISTED) {
                break;
            }
        }
        return devices;
    }

    /** One device in detail, including its live position from Redis. */
    public DeviceLoadView device(String imei) {
        Map<Object, Object> livePosition;
        try {
            livePosition = liveVehiclePositionStore.findFields(imei);
        } catch (RuntimeException problem) {
            livePosition = Map.of("problem", "Redis did not answer: " + problem.getMessage());
        }
        return new DeviceLoadView(imei, partitionOf(imei), messagesByStage(imei, allSnapshots()), livePosition);
    }

    /**
     * The partition Kafka's default partitioner picks for this IMEI: murmur2(key) % partitions.
     * Same IMEI, same partition, always - that is what keeps one device's frames in order.
     */
    static int partitionOf(String imei) {
        return Utils.toPositive(Utils.murmur2(imei.getBytes(StandardCharsets.UTF_8))) % KafkaTopicNames.PARTITIONS_PER_TOPIC;
    }

    private static Map<String, Long> messagesByStage(String imei, Map<PipelineStage, StageSnapshot> snapshots) {
        Map<String, Long> counts = new LinkedHashMap<>();
        snapshots.forEach((stage, snapshot) -> counts.put(stage.name(), snapshot.messagesByImei().getOrDefault(imei, 0L)));
        return counts;
    }

    private Map<PipelineStage, StageSnapshot> allSnapshots() {
        Map<PipelineStage, StageSnapshot> snapshots = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            snapshots.put(stage, metrics.snapshot(stage));
        }
        return snapshots;
    }

    private long storedPositions() {
        long now = System.currentTimeMillis();
        if (now - storedPositionsCountedAtMillis > STORED_COUNT_CACHE_MILLIS) {
            try {
                cachedStoredPositions = gpsPositionRepository.count();
                storedPositionsCountedAtMillis = now;
            } catch (RuntimeException problem) {
                return -1;
            }
        }
        return cachedStoredPositions;
    }
}
