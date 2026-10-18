package com.srm.gps.connectivity.kafka;

import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Asks Kafka itself how the platform looks right now: topics, partitions, offsets, consumer groups,
 * which consumer owns which partition, and how far behind (lag) each group is.
 * Uses Kafka's Admin API - the same information as the kafka-consumer-groups command.
 */
@Component
@OnlyInKafkaFlow
public class KafkaTopologyInspector {

    private static final long TIMEOUT_SECONDS = 5;

    private final KafkaAdmin kafkaAdmin;
    private volatile Admin admin;

    public KafkaTopologyInspector(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /**
     * @param partitions          oldest and newest offset per partition
     * @param messagesKept        newest - oldest, summed: messages Kafka still keeps (retention deletes older ones)
     * @param messagesEverWritten newest offsets summed: every message ever written to the topic
     */
    public record TopicView(String name, List<PartitionOffsets> partitions, long messagesKept, long messagesEverWritten) {
        static TopicView of(String name, List<PartitionOffsets> partitions) {
            return new TopicView(name, partitions,
                    partitions.stream().mapToLong(p -> p.latestOffset() - p.earliestOffset()).sum(),
                    partitions.stream().mapToLong(PartitionOffsets::latestOffset).sum());
        }
    }

    public record PartitionOffsets(int partition, long earliestOffset, long latestOffset) {
    }

    /** @param committedOffset -1 when the group has not committed anything on that partition yet */
    public record PartitionLag(String topic, int partition, long committedOffset, long latestOffset, long lag, String ownerClientId) {
    }

    public record GroupMember(String clientId, String host, List<String> assignedPartitions) {
    }

    public record ConsumerGroupView(String groupId, String state, List<GroupMember> members, List<PartitionLag> partitions) {
        public long totalLag() {
            return partitions.stream().mapToLong(PartitionLag::lag).sum();
        }
    }

    public List<TopicView> describeTopics(Collection<String> topicNames) throws Exception {
        Map<String, TopicDescription> descriptions = admin().describeTopics(topicNames).allTopicNames()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<TopicPartition> allPartitions = new ArrayList<>();
        descriptions.values().forEach(description -> description.partitions()
                .forEach(info -> allPartitions.add(new TopicPartition(description.name(), info.partition()))));
        Map<TopicPartition, Long> earliest = offsets(allPartitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = offsets(allPartitions, OffsetSpec.latest());

        List<TopicView> topics = new ArrayList<>();
        for (String topicName : topicNames) {
            TopicDescription description = descriptions.get(topicName);
            if (description == null) {
                continue;
            }
            List<PartitionOffsets> partitions = description.partitions().stream()
                    .map(info -> new TopicPartition(topicName, info.partition()))
                    .map(tp -> new PartitionOffsets(tp.partition(), earliest.getOrDefault(tp, 0L), latest.getOrDefault(tp, 0L)))
                    .sorted(Comparator.comparingInt(PartitionOffsets::partition))
                    .toList();
            topics.add(TopicView.of(topicName, partitions));
        }
        return topics;
    }

    public List<ConsumerGroupView> describeGroups(Map<String, String> topicOfGroup) throws Exception {
        Map<String, ConsumerGroupDescription> descriptions = admin().describeConsumerGroups(topicOfGroup.keySet()).all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<ConsumerGroupView> groups = new ArrayList<>();
        for (Map.Entry<String, String> entry : topicOfGroup.entrySet()) {
            String groupId = entry.getKey();
            String topic = entry.getValue();
            ConsumerGroupDescription description = descriptions.get(groupId);

            Map<TopicPartition, String> ownerOfPartition = new HashMap<>();
            List<GroupMember> members = new ArrayList<>();
            if (description != null) {
                for (MemberDescription member : description.members()) {
                    List<String> assigned = member.assignment().topicPartitions().stream()
                            .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                            .map(tp -> tp.topic() + "-" + tp.partition())
                            .toList();
                    member.assignment().topicPartitions().forEach(tp -> ownerOfPartition.put(tp, member.clientId()));
                    members.add(new GroupMember(member.clientId(), member.host(), assigned));
                }
            }

            Map<TopicPartition, OffsetAndMetadata> committed = admin().listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            TopicView topicView = describeTopics(List.of(topic)).stream().findFirst().orElse(TopicView.of(topic, List.of()));

            List<PartitionLag> partitions = topicView.partitions().stream().map(offsets -> {
                TopicPartition tp = new TopicPartition(topic, offsets.partition());
                OffsetAndMetadata committedOffset = committed.get(tp);
                long committedValue = committedOffset == null ? -1 : committedOffset.offset();
                long lag = committedOffset == null ? offsets.latestOffset() - offsets.earliestOffset() : offsets.latestOffset() - committedValue;
                return new PartitionLag(topic, offsets.partition(), committedValue, offsets.latestOffset(), Math.max(lag, 0),
                        ownerOfPartition.getOrDefault(tp, ""));
            }).toList();

            String state = description == null ? "UNKNOWN" : description.groupState().toString();
            groups.add(new ConsumerGroupView(groupId, state, members, partitions));
        }
        return groups;
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> answer = admin().listOffsets(request).all()
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Map<TopicPartition, Long> result = new HashMap<>();
        answer.forEach((tp, info) -> result.put(tp, info.offset()));
        return result;
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
