package com.srm.gps.application.engine;

import com.srm.gps.application.simulator.SimulationStatus;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector.GroupMember;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector.PartitionLag;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector.TopicView;
import com.srm.gps.connectivity.monitoring.StageSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The JSON shapes of the engine API (GET /api/engine/...). */
public final class EngineViews {

    private EngineViews() {
    }

    /** Everything on the engine dashboard in one answer. */
    public record EngineOverview(
            String processingFlow,
            Instant generatedAt,
            SimulationStatus simulator,
            List<StageView> producers,
            List<ConsumerView> consumers,
            StageView directFlow,
            List<TopicView> topics,
            String kafkaProblem,
            long storedPositions,
            long vehiclesWithLivePosition,
            String redisProblem) {
    }

    /** The load of one producer or consumer, as counted inside this service. */
    public record StageView(
            String stage,
            String label,
            String topic,
            String consumerGroup,
            long totalMessages,
            long failures,
            double messagesPerSecond,
            List<Long> lastMinutePerSecond,
            Map<Integer, Long> messagesByPartition,
            int devicesSeen,
            Instant lastMessageAt) {

        static StageView from(StageSnapshot snapshot) {
            return new StageView(snapshot.stage().name(), snapshot.stage().label(), snapshot.stage().topic(),
                    snapshot.stage().consumerGroup(), snapshot.totalMessages(), snapshot.failures(),
                    snapshot.messagesPerSecond(), snapshot.lastMinutePerSecond(), snapshot.messagesByPartition(),
                    snapshot.messagesByImei().size(), snapshot.lastMessageAt());
        }
    }

    /** A consumer: our counters plus what Kafka says about its group (members, partitions, lag). */
    public record ConsumerView(
            StageView load,
            String groupState,
            List<GroupMember> members,
            List<PartitionLag> partitions,
            long totalLag) {
    }

    /** How much of one device's traffic each producer/consumer handled. */
    public record DeviceLoadView(
            String imei,
            int partition,
            Map<String, Long> messagesByStage,
            Map<Object, Object> livePosition) {
    }
}
