package com.srm.gps.application.kafkastreams;

import com.srm.gps.streams.analytics.ActivityWindow;
import com.srm.gps.streams.analytics.ParityTally;
import com.srm.gps.streams.analytics.VehicleActivity;
import com.srm.gps.streams.analytics.VehicleSnapshot;
import com.srm.gps.streams.runtime.RestoreTracker.Restoration;
import com.srm.gps.streams.runtime.StreamsApp.StateChange;
import com.srm.gps.streams.runtime.StreamsIntrospection.InstanceView;
import com.srm.gps.streams.runtime.StreamsIntrospection.MetricsView;
import com.srm.gps.streams.runtime.StreamsIntrospection.StoreLagView;
import com.srm.gps.streams.runtime.StreamsIntrospection.ThreadView;
import com.srm.gps.streams.runtime.StreamsIntrospection.TopologyView;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The JSON shapes of the Kafka Streams API (GET /api/streams/...). */
public final class KafkaStreamsViews {

    private KafkaStreamsViews() {
    }

    /**
     * Everything about the Kafka Streams layer in one answer.
     *
     * @param enabled        false in the direct flow or with gps.streams.enabled=false (then reason says why)
     * @param normalization  owner | shadow | off
     * @param normalizer     who turns raw frames into positions right now: "Kafka Streams" or "listener"
     * @param instance       this instance's application.server
     */
    public record StreamsOverview(boolean enabled, String reason, String normalization, String normalizer, String instance,
                                  Instant generatedAt, SettingsView settings, List<AppView> apps) {

        static StreamsOverview disabled(String reason) {
            return new StreamsOverview(false, reason, null, "listener", null, Instant.now(), null, List.of());
        }
    }

    /** Durations in seconds. */
    public record SettingsView(long windowSizeSeconds, long windowGraceSeconds, long windowRetentionSeconds,
                               long maxLatenessSeconds, long seenReadingsRetentionSeconds, long parityGraceSeconds,
                               int threads, int standbyReplicas, int replicationFactor, long rocksdbOffHeapMb, String stateDir) {
    }

    /**
     * One Kafka Streams app.
     *
     * @param storeEntries keys per key-value store held on this instance (exact up to 10,000, then estimated)
     */
    public record AppView(String role, String applicationId, String purpose, String state, String guarantee,
                          Instant startedAt, int replacedThreads, String lastFailure, TopologyView topology,
                          List<ThreadView> threads, List<StoreLagView> storeLags, Map<String, Long> storeEntries,
                          List<Restoration> restorations, List<StateChange> stateChanges, List<InstanceView> instances,
                          MetricsView metrics) {
    }

    /**
     * One vehicle from the KTable, with where Kafka Streams keeps it.
     *
     * @param partition    the partition (= task) that owns the vehicle's state
     * @param servedBy     the instance that answered
     * @param standbyHosts instances with a warm copy of that state
     * @param windows      newest activity windows first
     */
    public record VehicleView(VehicleSnapshot snapshot, int partition, String servedBy, List<String> standbyHosts,
                              List<VehicleActivity> windows) {
    }

    /** All vehicles in one window, summed over every instance's share (open = the grace period has not passed yet). */
    public record FleetWindow(Instant windowStart, Instant windowEnd, boolean open, int vehicles, long readings,
                              long movingReadings, long outOfOrderReadings, int maxSpeedKmph, double averageSpeedKmph,
                              double movingShare, double distanceMeters) {

        static FleetWindow of(Instant start, Instant end, boolean open, int vehicles, ActivityWindow sum) {
            VehicleActivity rounded = VehicleActivity.of(null, start, end, sum);
            return new FleetWindow(start, end, open, vehicles, sum.readings(), sum.movingReadings(), sum.outOfOrderReadings(),
                    sum.maxSpeedKmph(), rounded.averageSpeedKmph(), rounded.movingShare(), rounded.distanceMeters());
        }
    }

    /** One instance's share of the fleet windows, before merging (the peer-to-peer shape). */
    public record WindowShare(Instant windowStart, Instant windowEnd, int vehicles, ActivityWindow activity) {
    }

    /**
     * Shadow parity. Rejections are compared as counts since the start; accepted readings one by one.
     *
     * @param active false when not in shadow mode (note explains)
     */
    public record ParityView(boolean active, String note, ParityTally totals, long streamsAccepted, long streamsRejected,
                             long listenerAccepted, long listenerRejected, List<VehicleParity> vehicles) {
    }

    public record VehicleParity(String imei, ParityTally tally) {
    }
}
