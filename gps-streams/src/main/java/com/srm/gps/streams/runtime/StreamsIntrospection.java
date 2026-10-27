package com.srm.gps.streams.runtime;

import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.LagInfo;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.TaskMetadata;
import org.apache.kafka.streams.ThreadMetadata;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a running Kafka Streams app looks like from the inside, in the terms of the architecture guide:
 * sub-topologies and their processors, stream threads and the tasks they run (one task = one sub-topology x one
 * partition), active and standby tasks, store lag, the other instances of the app and the throughput metrics.
 */
public final class StreamsIntrospection {

    public record TopologyView(List<SubtopologyView> subtopologies) {
    }

    /** A connected part of the topology; Kafka Streams creates one task per input partition of it. */
    public record SubtopologyView(int id, List<String> sourceTopics, List<String> sinkTopics, List<String> stores, List<NodeView> nodes) {
    }

    /** @param kind SOURCE, PROCESSOR or SINK */
    public record NodeView(String name, String kind, List<String> topics, List<String> stores, List<String> next) {
    }

    public record ThreadView(String name, String state, List<TaskView> activeTasks, List<TaskView> standbyTasks) {
    }

    /** @param id the task id, "subtopology_partition", for example 0_2 */
    public record TaskView(String id, int subtopology, int partition, List<InputView> inputs) {
    }

    /** How far the task has read one input partition. Null offsets: not known yet (standby, or nothing committed). */
    public record InputView(String topic, int partition, Long committedOffset, Long endOffset, Long lag) {
    }

    /** How far this instance's copy of a store partition is behind its changelog (0 = caught up). */
    public record StoreLagView(String store, int partition, long position, long endOffset, long lag) {
    }

    /** One instance of the app (a JVM running it), with the partitions it is active and standby for. */
    public record InstanceView(String host, int port, boolean local, List<String> activePartitions, List<String> standbyPartitions) {
    }

    /** Summed over the app's stream threads (rates per second, latency in ms). */
    public record MetricsView(double processRate, double processTotal, double commitRate, double commitLatencyAvgMs,
                              double pollRate, double droppedRecordsTotal, int aliveStreamThreads) {
    }

    private StreamsIntrospection() {
    }

    public static TopologyView topology(Topology topology) {
        List<SubtopologyView> views = new ArrayList<>();
        topology.describe().subtopologies().stream()
                .sorted(Comparator.comparingInt(TopologyDescription.Subtopology::id))
                .forEach(subtopology -> views.add(subtopology(subtopology)));
        return new TopologyView(views);
    }

    private static SubtopologyView subtopology(TopologyDescription.Subtopology subtopology) {
        Set<String> sources = new TreeSet<>();
        Set<String> sinks = new TreeSet<>();
        Set<String> stores = new TreeSet<>();
        List<NodeView> nodes = new ArrayList<>();
        for (TopologyDescription.Node node : flowOrder(subtopology.nodes())) {
            List<String> next = node.successors().stream().map(TopologyDescription.Node::name).sorted().toList();
            switch (node) {
                case TopologyDescription.Source source -> {
                    List<String> topics = source.topicSet() == null ? List.of(String.valueOf(source.topicPattern()))
                            : source.topicSet().stream().sorted().toList();
                    sources.addAll(topics);
                    nodes.add(new NodeView(node.name(), "SOURCE", topics, List.of(), next));
                }
                case TopologyDescription.Sink sink -> {
                    List<String> topics = sink.topic() == null ? List.of("(dynamic)") : List.of(sink.topic());
                    sinks.addAll(topics);
                    nodes.add(new NodeView(node.name(), "SINK", topics, List.of(), next));
                }
                case TopologyDescription.Processor processor -> {
                    List<String> processorStores = processor.stores().stream().sorted().toList();
                    stores.addAll(processorStores);
                    nodes.add(new NodeView(node.name(), "PROCESSOR", List.of(), processorStores, next));
                }
                default -> nodes.add(new NodeView(node.name(), "NODE", List.of(), List.of(), next));
            }
        }
        return new SubtopologyView(subtopology.id(), List.copyOf(sources), List.copyOf(sinks), List.copyOf(stores), nodes);
    }

    /** Sources first, then every node after the nodes that feed it (breadth first), so the list reads as the data flows. */
    private static List<TopologyDescription.Node> flowOrder(Set<TopologyDescription.Node> nodes) {
        Deque<TopologyDescription.Node> queue = new ArrayDeque<>();
        nodes.stream().filter(node -> node.predecessors().isEmpty())
                .sorted(Comparator.comparing(TopologyDescription.Node::name)).forEach(queue::add);
        Set<String> seen = new HashSet<>();
        List<TopologyDescription.Node> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            TopologyDescription.Node node = queue.removeFirst();
            if (seen.add(node.name())) {
                ordered.add(node);
                node.successors().stream().sorted(Comparator.comparing(TopologyDescription.Node::name)).forEach(queue::addLast);
            }
        }
        nodes.stream().filter(node -> !seen.contains(node.name())).forEach(ordered::add);
        return ordered;
    }

    public static List<ThreadView> threads(KafkaStreams streams) {
        return streams.metadataForLocalThreads().stream()
                .sorted(Comparator.comparing(ThreadMetadata::threadName))
                .map(thread -> new ThreadView(shortThreadName(thread.threadName()), thread.threadState(),
                        tasks(thread.activeTasks()), tasks(thread.standbyTasks())))
                .toList();
    }

    private static List<TaskView> tasks(Set<TaskMetadata> tasks) {
        return tasks.stream()
                .sorted(Comparator.comparing(task -> task.taskId().toString()))
                .map(task -> new TaskView(task.taskId().toString(), task.taskId().subtopology(), task.taskId().partition(),
                        task.topicPartitions().stream()
                                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                                .map(tp -> input(tp, task.committedOffsets().get(tp), task.endOffsets().get(tp)))
                                .toList()))
                .toList();
    }

    private static InputView input(TopicPartition tp, Long committed, Long end) {
        Long known = committed == null || committed < 0 ? null : committed;
        Long knownEnd = end == null || end < 0 ? null : end;
        Long lag = known == null || knownEnd == null ? null : Math.max(0, knownEnd - known);
        return new InputView(tp.topic(), tp.partition(), known, knownEnd, lag);
    }

    public static List<StoreLagView> storeLags(KafkaStreams streams) {
        List<StoreLagView> lags = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, LagInfo>> store : streams.allLocalStorePartitionLags().entrySet()) {
            store.getValue().forEach((partition, lag) -> lags.add(new StoreLagView(store.getKey(), partition,
                    lag.currentOffsetPosition(), lag.endOffsetPosition(), lag.offsetLag())));
        }
        lags.sort(Comparator.comparing(StoreLagView::store).thenComparingInt(StoreLagView::partition));
        return lags;
    }

    /** @param self this instance's application.server ("host:port") */
    public static List<InstanceView> instances(KafkaStreams streams, String self) {
        return streams.metadataForAllStreamsClients().stream()
                .sorted(Comparator.comparing(StreamsMetadata::host).thenComparingInt(StreamsMetadata::port))
                .map(instance -> new InstanceView(instance.host(), instance.port(),
                        (instance.host() + ":" + instance.port()).equals(self),
                        partitions(instance.topicPartitions()), partitions(instance.standbyTopicPartitions())))
                .toList();
    }

    private static List<String> partitions(Set<TopicPartition> partitions) {
        Set<String> names = new LinkedHashSet<>();
        partitions.stream().sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                .forEach(tp -> names.add(tp.topic() + "-" + tp.partition()));
        return List.copyOf(names);
    }

    public static MetricsView metrics(KafkaStreams streams) {
        double processRate = 0;
        double processTotal = 0;
        double commitRate = 0;
        double commitLatency = 0;
        int commitLatencySamples = 0;
        double pollRate = 0;
        double dropped = 0;
        int alive = 0;
        for (Map.Entry<MetricName, ? extends Metric> entry : streams.metrics().entrySet()) {
            MetricName name = entry.getKey();
            double value = number(entry.getValue().metricValue());
            switch (name.group()) {
                case "stream-thread-metrics" -> {
                    switch (name.name()) {
                        case "process-rate" -> processRate += value;
                        case "process-total" -> processTotal += value;
                        case "commit-rate" -> commitRate += value;
                        case "poll-rate" -> pollRate += value;
                        case "commit-latency-avg" -> {
                            if (value > 0) {
                                commitLatency += value;
                                commitLatencySamples++;
                            }
                        }
                        default -> {
                        }
                    }
                }
                case "stream-task-metrics" -> {
                    if (name.name().equals("dropped-records-total")) {
                        dropped += value;
                    }
                }
                case "stream-metrics" -> {
                    if (name.name().equals("alive-stream-threads")) {
                        alive = (int) value;
                    }
                }
                default -> {
                }
            }
        }
        return new MetricsView(round(processRate), processTotal, round(commitRate),
                round(commitLatencySamples == 0 ? 0 : commitLatency / commitLatencySamples), round(pollRate), dropped, alive);
    }

    /** "gps-streams-analytics-0f3c...-StreamThread-2" -> "StreamThread-2". */
    private static String shortThreadName(String threadName) {
        int at = threadName.lastIndexOf("StreamThread-");
        return at >= 0 ? threadName.substring(at) : threadName;
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? 0 : d;
        }
        return 0;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
