package com.srm.gps.application.modules;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleDescriptor;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Replays one module: every reading since a moment in the past is handed to it again.
 *
 * Kafka keeps gps.processed-positions for 31 days, so a module whose logic was fixed, whose settings changed, or
 * whose consumer failed for a while can be run over the old readings again:
 *   1. stop the module's consumers (the group must be empty to move its bookmark);
 *   2. delete its Redis state (ModuleDescriptor#statePatterns) and call GpsModule#beforeReplay, so replayed readings
 *      are not taken for old ones;
 *   3. move the group's committed offset on every partition to the first reading stored at or after the moment
 *      (Kafka's offsets-for-times; the oldest kept reading when the moment is older than the retention);
 *   4. start the consumers again: they read from there to the end and carry on live.
 * Only this module rereads; every other consumer group keeps its own bookmark. Events are keyed by vehicle, type and
 * start, so an episode found again is the same row, not a second one.
 */
@Service
@OnlyInKafkaFlow
public class ModuleReplayService {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleReplayService.class);
    private static final long TIMEOUT_SECONDS = 15;

    public record PartitionReplay(int partition, long fromOffset, long endOffset, long readingsToReplay) {
    }

    public record ReplayReport(String module, String consumerGroup, Instant from, long stateKeysDeleted,
                               List<PartitionReplay> partitions, long readingsToReplay) {
    }

    private final ModuleRegistry modules;
    private final KafkaListenerEndpointRegistry listeners;
    private final KafkaAdmin kafkaAdmin;
    private final RedisStateStore redis;
    private volatile Admin admin;

    public ModuleReplayService(ModuleRegistry modules, KafkaListenerEndpointRegistry listeners, KafkaAdmin kafkaAdmin, RedisStateStore redis) {
        this.modules = modules;
        this.listeners = listeners;
        this.kafkaAdmin = kafkaAdmin;
        this.redis = redis;
    }

    /** Whether the module's consumers are running right now. */
    public boolean running(String moduleId) {
        MessageListenerContainer container = listeners.getListenerContainer(ModuleKafkaListeners.containerId(moduleId));
        return container != null && container.isRunning();
    }

    public synchronized ReplayReport replay(String moduleId, Instant from) throws Exception {
        GpsModule module = modules.find(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("There is no module " + moduleId));
        ModuleDescriptor d = module.descriptor();
        MessageListenerContainer container = listeners.getListenerContainer(ModuleKafkaListeners.containerId(moduleId));
        if (!modules.enabled(module) || container == null) {
            throw new IllegalStateException("Module " + moduleId + " is switched off (gps.modules." + moduleId + ".enabled)");
        }
        if (from.isAfter(Instant.now())) {
            throw new IllegalArgumentException("A replay starts in the past, not at " + from);
        }

        LOG.warn("Replay of module {} from {}: stopping its consumers", moduleId, from);
        container.stop();
        try {
            long deleted = 0;
            for (String pattern : d.statePatterns()) {
                Set<String> keys = redis.scanKeys(pattern);
                if (!keys.isEmpty()) {
                    deleted += redis.template().delete(keys);
                }
            }
            module.beforeReplay(from);

            List<TopicPartition> partitions = new ArrayList<>();
            for (int p = 0; p < KafkaTopicNames.PARTITIONS_PER_TOPIC; p++) {
                partitions.add(new TopicPartition(KafkaTopicNames.PROCESSED_POSITIONS, p));
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> atMoment = offsets(partitions, OffsetSpec.forTimestamp(from.toEpochMilli()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = offsets(partitions, OffsetSpec.latest());

            Map<TopicPartition, OffsetAndMetadata> bookmarks = new HashMap<>();
            List<PartitionReplay> report = new ArrayList<>();
            long total = 0;
            for (TopicPartition tp : partitions) {
                long end = latest.get(tp).offset();
                long start = atMoment.get(tp).offset();
                // -1: nothing at or after the moment on this partition, so there is nothing to reread there
                long offset = start < 0 ? end : start;
                bookmarks.put(tp, new OffsetAndMetadata(offset, "replay from " + from));
                report.add(new PartitionReplay(tp.partition(), offset, end, end - offset));
                total += end - offset;
            }
            admin().alterConsumerGroupOffsets(d.consumerGroup(), bookmarks).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.warn("Replay of module {}: group {} moved back to {} ({} readings to replay, {} state keys deleted)",
                    moduleId, d.consumerGroup(), from, total, deleted);
            return new ReplayReport(moduleId, d.consumerGroup(), from, deleted, report, total);
        } finally {
            container.start();
        }
    }

    private Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets(List<TopicPartition> partitions, OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));
        return admin().listOffsets(request).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private Admin admin() {
        if (admin == null) {
            synchronized (this) {
                if (admin == null) {
                    admin = Admin.create(kafkaAdmin.getConfigurationProperties());
                }
            }
        }
        return admin;
    }

    @PreDestroy
    public void closeAdmin() {
        if (admin != null) {
            admin.close();
        }
    }
}
