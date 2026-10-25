package com.srm.gps.application.kafkastreams;

import com.srm.gps.application.processing.DatabaseBackedPreviousReadingStore;
import com.srm.gps.application.processing.GpsProcessingConfiguration.DistanceProperties;
import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.connectivity.flow.OnlyWithKafkaStreams;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.streams.GpsStreamsTopologies;
import com.srm.gps.streams.StreamsObserver;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.normalize.PreviousReadingFallback;
import com.srm.gps.streams.runtime.BoundedMemoryRocksDbConfig;
import com.srm.gps.streams.runtime.StreamsApp;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs the platform's Kafka Streams apps inside the service and stops them cleanly with it.
 *
 *   normalizer (unless normalization=off)  application.id = gps-raw-frame-processing-service (owner)
 *                                                          or gps-streams-shadow-normalizer  (shadow)
 *   analytics  (always)                    application.id = gps-streams-analytics
 *
 * Each app gets threads = partitions, so every task (sub-topology x partition) has a thread of its own, and
 * application.server = this instance, so Interactive Queries can find which instance holds which vehicle.
 * Add an instance and Kafka rebalances the tasks over both - the state follows through the changelogs.
 */
@Component
@OnlyWithKafkaStreams
public class GpsStreamsRuntime implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(GpsStreamsRuntime.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(20);

    private final KafkaStreamsProperties settings;
    private final TopologySettings topologySettings;
    private final KafkaAdmin kafkaAdmin;
    private final String applicationServer;
    private final StreamsApp normalizer;
    private final StreamsApp analytics;
    private final LongAdder shadowAccepted = new LongAdder();
    private final LongAdder shadowRejected = new LongAdder();
    private volatile boolean running;

    public GpsStreamsRuntime(KafkaStreamsProperties settings, DistanceProperties distance, KafkaAdmin kafkaAdmin,
                             PipelineMetrics metrics, DatabaseBackedPreviousReadingStore previousReadings,
                             @Value("${server.port:8080}") int serverPort) {
        this.settings = settings;
        this.topologySettings = settings.topologySettings(distance.toSettings());
        this.kafkaAdmin = kafkaAdmin;
        this.applicationServer = settings.advertisedHostOrDefault() + ":" + serverPort;

        StreamsNormalization mode = topologySettings.normalization();
        this.normalizer = switch (mode) {
            case OWNER -> new StreamsApp("normalizer", normalizerProperties(KafkaConsumerGroupNames.RAW_FRAME_PROCESSING, "earliest"),
                    () -> GpsStreamsTopologies.normalizer(topologySettings, previousReadings::findStoredReading, ownerObserver(metrics)),
                    settings.leaveGroupOnCloseOrDefault());
            // the shadow measures from its own state only: the database is being written by the listener meanwhile
            case SHADOW -> new StreamsApp("normalizer", normalizerProperties(KafkaConsumerGroupNames.STREAMS_SHADOW_NORMALIZER, "latest"),
                    () -> GpsStreamsTopologies.normalizer(topologySettings, PreviousReadingFallback.NONE, shadowObserver()),
                    settings.leaveGroupOnCloseOrDefault());
            case OFF -> null;
        };
        this.analytics = new StreamsApp("analytics", analyticsProperties(mode),
                () -> GpsStreamsTopologies.analytics(topologySettings, new StreamsObserver() {
                    @Override
                    public void positionAnalyzed(String imei, int partition) {
                        metrics.recordMessage(PipelineStage.STREAMS_ANALYTICS, imei, partition);
                    }
                }), settings.leaveGroupOnCloseOrDefault());
    }

    /** Owner: Kafka Streams IS consumer 1 and producers 2 and 3 - counted under the same stages as the listener. */
    private static StreamsObserver ownerObserver(PipelineMetrics metrics) {
        return new StreamsObserver() {
            @Override
            public void frameNormalized(String imei, int partition, boolean accepted) {
                metrics.recordMessage(PipelineStage.RAW_FRAME_PROCESSING, imei, partition);
                metrics.recordMessage(accepted ? PipelineStage.PROCESSED_POSITION_PRODUCER : PipelineStage.REJECTED_FRAME_PRODUCER,
                        imei, partition);
            }
        };
    }

    /** Shadow: counted apart, so the real pipeline's numbers stay the listener's. */
    private StreamsObserver shadowObserver() {
        return new StreamsObserver() {
            @Override
            public void frameNormalized(String imei, int partition, boolean accepted) {
                (accepted ? shadowAccepted : shadowRejected).increment();
            }
        };
    }

    private Properties normalizerProperties(String applicationId, String firstStartOffset) {
        Properties properties = common(applicationId, settings.normalizerGuaranteeOrDefault());
        // Only for a group without committed offsets. The owner takes the listener's group over, offsets included.
        properties.put(StreamsConfig.mainConsumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), firstStartOffset);
        return properties;
    }

    private Properties analyticsProperties(StreamsNormalization mode) {
        Properties properties = common(KafkaConsumerGroupNames.STREAMS_ANALYTICS, settings.analyticsGuaranteeOrDefault());
        // live analytics: a brand-new app starts at the end instead of folding a week of history into "latest state"
        properties.put(StreamsConfig.mainConsumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "latest");
        // at-least-once commits every 30 s by default; every second keeps the group's lag (and a replay after a
        // crash) small
        properties.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1_000);
        if (mode == StreamsNormalization.SHADOW) {
            // wait a moment for the slower of the two outputs, so the parity join sees both sides in timestamp order
            properties.put(StreamsConfig.MAX_TASK_IDLE_MS_CONFIG, 1_000);
        }
        return properties;
    }

    private Properties common(String applicationId, String guarantee) {
        Properties properties = new Properties();
        // bootstrap servers and any security settings, exactly as the rest of the service connects
        kafkaAdmin.getConfigurationProperties().forEach((key, value) -> {
            if (key.equals(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG) || key.startsWith("security.")
                    || key.startsWith("sasl.") || key.startsWith("ssl.")) {
                properties.put(key, value);
            }
        });
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        properties.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, guarantee);
        properties.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, settings.threadsOrDefault());
        properties.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, settings.standbyReplicasOrDefault());
        properties.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, settings.replicationFactorOrDefault());
        properties.put(StreamsConfig.APPLICATION_SERVER_CONFIG, applicationServer);
        // the classic group protocol, like the listeners: the owner shares the listener's group
        properties.put(StreamsConfig.GROUP_PROTOCOL_CONFIG, "classic");
        if (settings.stateDir() != null && !settings.stateDir().isBlank()) {
            properties.put(StreamsConfig.STATE_DIR_CONFIG, settings.stateDir());
        }
        // a record that cannot be read is logged and skipped, not a reason to stop the app
        properties.put(StreamsConfig.DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, LogAndContinueExceptionHandler.class);
        properties.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, BoundedMemoryRocksDbConfig.class);
        properties.put(BoundedMemoryRocksDbConfig.TOTAL_OFF_HEAP_BYTES, settings.rocksdbOffHeapBytes());
        // never read what an aborted normalizer transaction wrote (exactly-once already implies it)
        properties.put(StreamsConfig.consumerPrefix(ConsumerConfig.ISOLATION_LEVEL_CONFIG), "read_committed");
        properties.putAll(settings.propertiesOrEmpty());
        return properties;
    }

    @Override
    public void start() {
        LOG.info("Kafka Streams: normalization={} (listener {}), analytics on, application.server={}",
                topologySettings.normalization().propertyValue(),
                topologySettings.normalization() == StreamsNormalization.OWNER ? "off" : "on", applicationServer);
        normalizer().ifPresent(StreamsApp::start);
        analytics.start();
        running = true;
    }

    @Override
    public void stop() {
        stopAll();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Closes both apps; they stay stopped until {@link #resetAll()} or the next start. */
    public void stopAll() {
        analytics.stop(CLOSE_TIMEOUT);
        normalizer().ifPresent(app -> app.stop(CLOSE_TIMEOUT));
    }

    /** Both apps from nothing (demo reset): see {@link StreamsApp#reset}. */
    public List<String> resetAll() {
        List<String> notes = new ArrayList<>();
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            normalizer().ifPresent(app -> notes.addAll(app.reset(admin, CLOSE_TIMEOUT)));
            notes.addAll(analytics.reset(admin, CLOSE_TIMEOUT));
        }
        shadowAccepted.reset();
        shadowRejected.reset();
        return notes;
    }

    public Optional<StreamsApp> normalizer() {
        return Optional.ofNullable(normalizer);
    }

    public StreamsApp analytics() {
        return analytics;
    }

    public List<StreamsApp> apps() {
        return normalizer == null ? List.of(analytics) : List.of(normalizer, analytics);
    }

    public StreamsNormalization mode() {
        return topologySettings.normalization();
    }

    public TopologySettings topologySettings() {
        return topologySettings;
    }

    public KafkaStreamsProperties settings() {
        return settings;
    }

    /** This instance as other instances see it: application.server. */
    public String applicationServer() {
        return applicationServer;
    }

    public long shadowAccepted() {
        return shadowAccepted.sum();
    }

    public long shadowRejected() {
        return shadowRejected.sum();
    }
}
