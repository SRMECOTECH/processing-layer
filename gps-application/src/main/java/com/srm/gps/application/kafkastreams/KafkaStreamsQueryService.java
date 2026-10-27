package com.srm.gps.application.kafkastreams;

import com.srm.gps.application.kafkastreams.KafkaStreamsViews.AppView;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.FleetWindow;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.ParityView;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.SettingsView;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.StreamsOverview;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.VehicleParity;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.VehicleView;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.WindowShare;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.connectivity.flow.OnlyWithKafkaStreams;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.processing.step.PreviousReading;
import com.srm.gps.streams.StoreNames;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.analytics.ActivityWindow;
import com.srm.gps.streams.analytics.ParityTally;
import com.srm.gps.streams.analytics.VehicleActivity;
import com.srm.gps.streams.analytics.VehicleSnapshot;
import com.srm.gps.streams.runtime.StreamsApp;
import com.srm.gps.streams.runtime.StreamsIntrospection;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Interactive Queries: reads the Kafka Streams state stores directly - no database, no extra topic.
 *
 * Each instance holds only the partitions (vehicles) whose tasks it runs. So:
 *   one vehicle  -> ask Kafka Streams which instance is active for the key, answer locally or forward to it;
 *   whole fleet  -> scatter-gather: every instance answers for its own share (/api/streams/local/...), merged here.
 * With one instance everything is local; with five, the same calls still give the whole picture.
 */
@Service
@OnlyWithKafkaStreams
public class KafkaStreamsQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStreamsQueryService.class);
    private static final Duration PEER_TIMEOUT = Duration.ofSeconds(3);
    private static final int EXACT_COUNT_LIMIT = 10_000;

    private final GpsStreamsRuntime runtime;
    private final PipelineMetrics metrics;
    private final RestClient peers;

    public KafkaStreamsQueryService(GpsStreamsRuntime runtime, PipelineMetrics metrics) {
        this.runtime = runtime;
        this.metrics = metrics;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(PEER_TIMEOUT).build());
        requestFactory.setReadTimeout(PEER_TIMEOUT);
        this.peers = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Thrown while an app is starting, rebalancing or restoring - try again in a moment (HTTP 503). */
    public static class StreamsUnavailableException extends RuntimeException {
        public StreamsUnavailableException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- overview

    public StreamsOverview overview() {
        TopologySettings t = runtime.topologySettings();
        KafkaStreamsProperties s = runtime.settings();
        SettingsView settings = new SettingsView(t.windowSize().toSeconds(), t.windowGrace().toSeconds(),
                t.windowRetention().toSeconds(), t.maxLateness().toSeconds(), t.seenReadingsRetention().toSeconds(),
                t.parityGrace().toSeconds(), s.threadsOrDefault(), s.standbyReplicasOrDefault(), s.replicationFactorOrDefault(),
                s.rocksdbOffHeapBytes() / (1024 * 1024), s.stateDir());
        List<AppView> apps = runtime.apps().stream().map(this::appView).toList();
        return new StreamsOverview(true, null, runtime.mode().propertyValue(),
                runtime.mode() == StreamsNormalization.OWNER ? "Kafka Streams" : "listener",
                runtime.applicationServer(), Instant.now(), settings, apps);
    }

    private AppView appView(StreamsApp app) {
        Optional<KafkaStreams> streams = app.kafkaStreams();
        Map<String, Long> entries = new LinkedHashMap<>();
        for (String store : keyValueStores(app)) {
            try {
                entries.put(store, countKeys(app.store(store, QueryableStoreTypes.<String, Object>keyValueStore())));
            } catch (RuntimeException notReady) {
                entries.put(store, null);
            }
        }
        return new AppView(app.role(), app.applicationId(), purpose(app),
                app.state(), app.properties().getProperty(StreamsConfig.PROCESSING_GUARANTEE_CONFIG), app.startedAt(),
                app.replacedThreads(), app.lastFailure(), StreamsIntrospection.topology(app.topology()),
                streams.map(ks -> quietly(() -> StreamsIntrospection.threads(ks), List.<StreamsIntrospection.ThreadView>of())).orElse(List.of()),
                streams.map(ks -> quietly(() -> StreamsIntrospection.storeLags(ks), List.<StreamsIntrospection.StoreLagView>of())).orElse(List.of()),
                entries, app.restores().restorations(), app.stateChanges(),
                streams.map(ks -> quietly(() -> StreamsIntrospection.instances(ks, runtime.applicationServer()),
                        List.<StreamsIntrospection.InstanceView>of())).orElse(List.of()),
                streams.map(StreamsIntrospection::metrics).orElse(null));
    }

    /**
     * Keys in a local store: counted up to {@value #EXACT_COUNT_LIMIT}, RocksDB's estimate above that. The estimate
     * alone is far off for a store that is mostly overwritten (one key per vehicle, updated every reading).
     */
    private static long countKeys(ReadOnlyKeyValueStore<String, Object> store) {
        long counted = 0;
        try (KeyValueIterator<String, Object> keys = store.all()) {
            while (keys.hasNext() && counted < EXACT_COUNT_LIMIT) {
                keys.next();
                counted++;
            }
        }
        return counted < EXACT_COUNT_LIMIT ? counted : store.approximateNumEntries();
    }

    private List<String> keyValueStores(StreamsApp app) {
        if (app == runtime.analytics()) {
            return runtime.mode() == StreamsNormalization.SHADOW
                    ? List.of(StoreNames.VEHICLE_TABLE, StoreNames.PARITY_TALLY) : List.of(StoreNames.VEHICLE_TABLE);
        }
        return List.of(StoreNames.PREVIOUS_READINGS);
    }

    private String purpose(StreamsApp app) {
        if (app == runtime.analytics()) {
            return "Vehicle table (KTable), 5-minute event-time activity windows"
                    + (runtime.mode() == StreamsNormalization.SHADOW ? " and the shadow parity join" : "");
        }
        return runtime.mode() == StreamsNormalization.OWNER
                ? "Owns normalization: raw frames to processed or rejected, exactly-once, state in RocksDB + changelog"
                : "Shadow normalization: the same logic into gps.shadow.*, compared with the listener's output";
    }

    // ---------------------------------------------------------------- vehicle table

    public List<VehicleSnapshot> vehicles(int limit) {
        List<VehicleSnapshot> all = new ArrayList<>(localVehicles(limit));
        for (String peer : peers()) {
            all.addAll(fromPeer(peer, "/api/streams/local/vehicles?limit=" + limit, new ParameterizedTypeReference<List<VehicleSnapshot>>() {
            }));
        }
        all.sort(Comparator.comparing(VehicleSnapshot::recordedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    public List<VehicleSnapshot> localVehicles(int limit) {
        ReadOnlyKeyValueStore<String, VehicleSnapshot> table = analyticsStore(StoreNames.VEHICLE_TABLE, QueryableStoreTypes.keyValueStore());
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        try (KeyValueIterator<String, VehicleSnapshot> all = table.all()) {
            while (all.hasNext() && vehicles.size() < limit) {
                vehicles.add(all.next().value);
            }
        }
        return vehicles;
    }

    public VehicleView vehicle(String imei, int windows) {
        KeyQueryMetadata owner = analyticsStreams().queryMetadataForKey(StoreNames.VEHICLE_TABLE, imei, Serdes.String().serializer());
        if (owner == null || KeyQueryMetadata.NOT_AVAILABLE.equals(owner)) {
            throw new StreamsUnavailableException("No instance owns this key yet (Kafka Streams is rebalancing)");
        }
        String active = server(owner.activeHost());
        if (!active.equals(runtime.applicationServer())) {
            return peers.get().uri("http://" + active + "/api/streams/local/vehicles/{imei}?windows={windows}", imei, windows)
                    .retrieve().body(VehicleView.class);
        }
        return localVehicle(imei, windows);
    }

    public VehicleView localVehicle(String imei, int windows) {
        KafkaStreams streams = analyticsStreams();
        KeyQueryMetadata owner = streams.queryMetadataForKey(StoreNames.VEHICLE_TABLE, imei, Serdes.String().serializer());
        VehicleSnapshot snapshot = analyticsStore(StoreNames.VEHICLE_TABLE, QueryableStoreTypes.<String, VehicleSnapshot>keyValueStore()).get(imei);
        ReadOnlyWindowStore<String, ActivityWindow> store = analyticsStore(StoreNames.ACTIVITY_WINDOWS, QueryableStoreTypes.windowStore());
        List<VehicleActivity> activity = new ArrayList<>();
        try (var newestFirst = store.backwardFetch(imei, Instant.EPOCH, Instant.now().plus(Duration.ofDays(1)))) {
            while (newestFirst.hasNext() && activity.size() < windows) {
                KeyValue<Long, ActivityWindow> window = newestFirst.next();
                Instant start = Instant.ofEpochMilli(window.key);
                activity.add(VehicleActivity.of(imei, start, start.plus(runtime.topologySettings().windowSize()), window.value));
            }
        }
        List<String> standbys = owner == null ? List.of() : owner.standbyHosts().stream().map(KafkaStreamsQueryService::server).sorted().toList();
        return new VehicleView(snapshot, owner == null ? -1 : owner.partition(), runtime.applicationServer(), standbys, activity);
    }

    // ---------------------------------------------------------------- fleet windows

    /** The newest {@code windows} windows of the whole fleet, merged from every instance's share. */
    public List<FleetWindow> fleetActivity(int windows) {
        List<WindowShare> shares = new ArrayList<>(localWindowShares());
        for (String peer : peers()) {
            shares.addAll(fromPeer(peer, "/api/streams/local/fleet/activity", new ParameterizedTypeReference<List<WindowShare>>() {
            }));
        }
        TreeMap<Instant, WindowShare> merged = new TreeMap<>(Comparator.reverseOrder());
        for (WindowShare share : shares) {
            merged.merge(share.windowStart(), share, (a, b) ->
                    new WindowShare(a.windowStart(), a.windowEnd(), a.vehicles() + b.vehicles(), a.activity().merge(b.activity())));
        }
        Instant newestReading = merged.values().stream().map(share -> share.activity().lastRecordedAt())
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        Duration grace = runtime.topologySettings().windowGrace();
        return merged.values().stream().limit(windows)
                .map(share -> FleetWindow.of(share.windowStart(), share.windowEnd(),
                        share.windowEnd().plus(grace).isAfter(newestReading), share.vehicles(), share.activity()))
                .toList();
    }

    /** This instance's vehicles, summed per window. */
    public List<WindowShare> localWindowShares() {
        ReadOnlyWindowStore<String, ActivityWindow> store = analyticsStore(StoreNames.ACTIVITY_WINDOWS, QueryableStoreTypes.windowStore());
        Map<Instant, WindowShare> byStart = new TreeMap<>();
        try (KeyValueIterator<Windowed<String>, ActivityWindow> all = store.all()) {
            all.forEachRemaining(entry -> byStart.merge(entry.key.window().startTime(),
                    new WindowShare(entry.key.window().startTime(), entry.key.window().endTime(), 1, entry.value),
                    (a, b) -> new WindowShare(a.windowStart(), a.windowEnd(), a.vehicles() + 1, a.activity().merge(b.activity()))));
        }
        return List.copyOf(byStart.values());
    }

    // ---------------------------------------------------------------- shadow parity

    public ParityView parity(int limit) {
        long listenerAccepted = metrics.snapshot(PipelineStage.PROCESSED_POSITION_PRODUCER).totalMessages();
        long listenerRejected = metrics.snapshot(PipelineStage.REJECTED_FRAME_PRODUCER).totalMessages();
        if (runtime.mode() != StreamsNormalization.SHADOW) {
            return new ParityView(false, runtime.mode() == StreamsNormalization.OWNER
                    ? "Kafka Streams owns normalization, so there is nothing to compare against. Run gps.streams.normalization=shadow to validate it against the listener before a hand-over."
                    : "Kafka Streams does not normalize (gps.streams.normalization=off). Switch to shadow to compare it with the listener.",
                    ParityTally.empty(), 0, 0, listenerAccepted, listenerRejected, List.of());
        }
        List<VehicleParity> vehicles = new ArrayList<>(localParity());
        for (String peer : peers()) {
            vehicles.addAll(fromPeer(peer, "/api/streams/local/parity", new ParameterizedTypeReference<List<VehicleParity>>() {
            }));
        }
        ParityTally totals = vehicles.stream().map(VehicleParity::tally).reduce(ParityTally.empty(), ParityTally::merge);
        vehicles.sort(Comparator.comparingLong((VehicleParity v) -> v.tally().mismatched() + v.tally().onlyListener() + v.tally().onlyStreams())
                .reversed().thenComparing(VehicleParity::imei));
        return new ParityView(true, null, totals, runtime.shadowAccepted(), runtime.shadowRejected(), listenerAccepted,
                listenerRejected, vehicles.size() > limit ? vehicles.subList(0, limit) : vehicles);
    }

    public List<VehicleParity> localParity() {
        if (runtime.mode() != StreamsNormalization.SHADOW) {
            return List.of();
        }
        ReadOnlyKeyValueStore<String, ParityTally> store = analyticsStore(StoreNames.PARITY_TALLY, QueryableStoreTypes.keyValueStore());
        List<VehicleParity> vehicles = new ArrayList<>();
        try (KeyValueIterator<String, ParityTally> all = store.all()) {
            all.forEachRemaining(entry -> vehicles.add(new VehicleParity(entry.key, entry.value)));
        }
        return vehicles;
    }

    // ---------------------------------------------------------------- normalizer state

    /** What the owner normalizer measures the device's next reading from (this instance's store only). */
    public Optional<PreviousReading> previousReading(String imei) {
        return runtime.normalizer().filter(app -> runtime.mode() == StreamsNormalization.OWNER).map(app -> {
            try {
                return app.store(StoreNames.PREVIOUS_READINGS, QueryableStoreTypes.<String, PreviousReading>keyValueStore()).get(imei);
            } catch (RuntimeException notHere) {
                return null;
            }
        });
    }

    // ---------------------------------------------------------------- plumbing

    private KafkaStreams analyticsStreams() {
        KafkaStreams streams = runtime.analytics().kafkaStreams()
                .orElseThrow(() -> new StreamsUnavailableException("Kafka Streams analytics is not running"));
        if (streams.state() != KafkaStreams.State.RUNNING && streams.state() != KafkaStreams.State.REBALANCING) {
            throw new StreamsUnavailableException("Kafka Streams analytics is " + streams.state());
        }
        return streams;
    }

    private <T> T analyticsStore(String name, QueryableStoreType<T> type) {
        try {
            return runtime.analytics().store(name, type);
        } catch (InvalidStateStoreException notReady) {
            throw new StreamsUnavailableException("The " + name + " is not queryable yet (" + notReady.getMessage() + ")");
        }
    }

    /** Every other instance of the analytics app. */
    private List<String> peers() {
        try {
            return analyticsStreams().metadataForAllStreamsClients().stream()
                    .map(instance -> server(instance.hostInfo()))
                    .filter(server -> !server.equals(runtime.applicationServer()))
                    .distinct().toList();
        } catch (RuntimeException notReady) {
            return List.of();
        }
    }

    /** A peer that does not answer leaves a gap in the answer instead of failing it. */
    private <T> List<T> fromPeer(String peer, String path, ParameterizedTypeReference<List<T>> type) {
        try {
            List<T> answer = peers.get().uri("http://" + peer + path).retrieve().body(type);
            return answer == null ? List.of() : answer;
        } catch (RuntimeException unreachable) {
            LOG.warn("Kafka Streams instance {} did not answer {}: {}", peer, path, unreachable.getMessage());
            return List.of();
        }
    }

    private static String server(HostInfo host) {
        return host.host() + ":" + host.port();
    }

    private static <T> T quietly(Supplier<T> read, T otherwise) {
        try {
            return read.get();
        } catch (RuntimeException notReady) {
            return otherwise;
        }
    }
}
