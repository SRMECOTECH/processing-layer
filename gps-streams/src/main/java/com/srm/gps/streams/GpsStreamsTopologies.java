package com.srm.gps.streams;

import com.srm.gps.streams.analytics.AnalyticsTopology;
import com.srm.gps.streams.normalize.NormalizationTopology;
import com.srm.gps.streams.normalize.PreviousReadingFallback;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;

/**
 * The two Kafka Streams apps of the platform - two apps, not one, because they have different owners and needs:
 *
 *   normalizer : raw frames -> processed / rejected. On the critical path, exactly-once, small state per device.
 *   analytics  : processed positions -> vehicle table, activity windows, shadow parity. Off the critical path:
 *                it can lag, restart or be rebuilt without holding up a single reading.
 *
 * Separate application ids mean separate consumer groups, offsets, state directories and changelog topics,
 * so each one scales, fails and is reset on its own.
 */
public final class GpsStreamsTopologies {

    private GpsStreamsTopologies() {
    }

    public static Topology normalizer(TopologySettings settings, PreviousReadingFallback fallback, StreamsObserver observer) {
        StreamsBuilder builder = new StreamsBuilder();
        NormalizationTopology.addTo(builder, settings, fallback, observer);
        return builder.build();
    }

    public static Topology analytics(TopologySettings settings, StreamsObserver observer) {
        StreamsBuilder builder = new StreamsBuilder();
        AnalyticsTopology.addTo(builder, settings, observer);
        return builder.build();
    }
}
