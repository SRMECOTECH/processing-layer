package com.srm.gps.application.kafkastreams;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.processing.step.DistanceSettings;
import com.srm.gps.streams.TopologySettings;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * gps.streams.* in application.yml: the Kafka Streams layer. Every value is optional; a missing one falls back to
 * the default noted here.
 *
 * @param enabled               run Kafka Streams at all (Kafka flow only; default true)
 * @param normalization         owner | shadow | off (default owner), see {@link StreamsNormalization}
 * @param threads               stream threads per app (default 3 = partitions: one partition's task per thread)
 * @param standbyReplicas       warm copies of every store on OTHER instances (default 0: there is one instance);
 *                              with 2+ instances set 1, so a failed instance's tasks move to a caught-up copy
 * @param replicationFactor     of the internal changelog topics (default -1 = the broker's default; 3 on a cluster)
 * @param stateDir              where RocksDB keeps the stores (default: Kafka Streams' temp directory); keep it on a
 *                              volume so a restart reuses the local state instead of restoring it from changelogs
 * @param advertisedHost        host other instances reach this one at (application.server), for Interactive Queries
 * @param leaveGroupOnClose     leave the consumer group on shutdown (default true): a successor takes over at once
 * @param maxLateness           readings older than this behind their device's newest are stale (default 24 h)
 * @param seenReadingsRetention how long readings are remembered for duplicate detection (default 24 h)
 * @param windowSize            activity window size (default 5 min)
 * @param windowGrace           how late a reading may be and still count in its window (default 2 min)
 * @param windowRetention       how long closed windows stay queryable (default 6 h)
 * @param parityGrace           shadow mode: how long one side waits for the other (default 2 min)
 * @param rocksdbOffHeapMb      one memory ceiling for all RocksDB stores in the JVM (default 64)
 * @param normalizerGuarantee   default exactly_once_v2: outputs, state and offsets commit together
 * @param analyticsGuarantee    default at_least_once: off the critical path, cheaper; reads committed data only
 * @param properties            any other Kafka Streams setting, passed on as is to both apps
 *                              (for example client.rack, rack.aware.assignment.tags, rack.aware.assignment.strategy)
 */
@ConfigurationProperties("gps.streams")
public record KafkaStreamsProperties(
        Boolean enabled,
        String normalization,
        Integer threads,
        Integer standbyReplicas,
        Integer replicationFactor,
        String stateDir,
        String advertisedHost,
        Boolean leaveGroupOnClose,
        Duration maxLateness,
        Duration seenReadingsRetention,
        Duration windowSize,
        Duration windowGrace,
        Duration windowRetention,
        Duration parityGrace,
        Integer rocksdbOffHeapMb,
        String normalizerGuarantee,
        String analyticsGuarantee,
        Map<String, String> properties) {

    public StreamsNormalization mode() {
        return StreamsNormalization.fromProperty(normalization);
    }

    public int threadsOrDefault() {
        return threads != null ? threads : KafkaTopicNames.PARTITIONS_PER_TOPIC;
    }

    public int standbyReplicasOrDefault() {
        return standbyReplicas != null ? standbyReplicas : 0;
    }

    public int replicationFactorOrDefault() {
        return replicationFactor != null ? replicationFactor : -1;
    }

    public String advertisedHostOrDefault() {
        return advertisedHost != null && !advertisedHost.isBlank() ? advertisedHost : "localhost";
    }

    public boolean leaveGroupOnCloseOrDefault() {
        return leaveGroupOnClose == null || leaveGroupOnClose;
    }

    public long rocksdbOffHeapBytes() {
        return (rocksdbOffHeapMb != null ? rocksdbOffHeapMb : 64) * 1024L * 1024L;
    }

    public String normalizerGuaranteeOrDefault() {
        return normalizerGuarantee != null ? normalizerGuarantee : StreamsConfig.EXACTLY_ONCE_V2;
    }

    public String analyticsGuaranteeOrDefault() {
        return analyticsGuarantee != null ? analyticsGuarantee : StreamsConfig.AT_LEAST_ONCE;
    }

    public Map<String, String> propertiesOrEmpty() {
        return properties != null ? properties : Map.of();
    }

    public TopologySettings topologySettings(DistanceSettings distance) {
        TopologySettings d = TopologySettings.defaults();
        return new TopologySettings(mode(), distance,
                maxLateness != null ? maxLateness : d.maxLateness(),
                seenReadingsRetention != null ? seenReadingsRetention : d.seenReadingsRetention(),
                windowSize != null ? windowSize : d.windowSize(),
                windowGrace != null ? windowGrace : d.windowGrace(),
                windowRetention != null ? windowRetention : d.windowRetention(),
                parityGrace != null ? parityGrace : d.parityGrace());
    }
}
