package com.srm.gps.streams;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.streams.normalize.PreviousReadingFallback;
import com.srm.gps.streams.runtime.StreamsIntrospection;
import com.srm.gps.streams.runtime.StreamsIntrospection.SubtopologyView;
import com.srm.gps.streams.runtime.StreamsIntrospection.TopologyView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the topologies is part of the contract: store names become changelog topic names, and every
 * sub-topology gets one task per input partition. A change here changes state on the broker.
 */
class TopologyShapeTest {

    @Test
    void theNormalizerIsOneSubTopologyFromRawFramesToProcessedAndRejected() {
        TopologyView view = StreamsIntrospection.topology(
                GpsStreamsTopologies.normalizer(TopologySettings.defaults(), PreviousReadingFallback.NONE, StreamsObserver.NONE));

        assertEquals(1, view.subtopologies().size());
        SubtopologyView normalize = view.subtopologies().get(0);
        assertEquals(List.of(KafkaTopicNames.RAW_DEVICE_FRAMES), normalize.sourceTopics());
        assertEquals(List.of(KafkaTopicNames.PROCESSED_POSITIONS, KafkaTopicNames.REJECTED_FRAMES), normalize.sinkTopics());
        assertEquals(List.of(StoreNames.PREVIOUS_READINGS, StoreNames.SEEN_READINGS), normalize.stores());
        assertEquals("SOURCE", normalize.nodes().get(0).kind(), "nodes are listed in flow order");
    }

    @Test
    void theAnalyticsNeedNoRepartitionTopicBecauseTheKeyIsAlreadyTheImei() {
        TopologyView view = StreamsIntrospection.topology(GpsStreamsTopologies.analytics(TopologySettings.defaults(), StreamsObserver.NONE));

        assertEquals(1, view.subtopologies().size(), "a second sub-topology would mean a repartition topic");
        SubtopologyView analytics = view.subtopologies().get(0);
        assertEquals(List.of(KafkaTopicNames.PROCESSED_POSITIONS), analytics.sourceTopics());
        assertEquals(List.of(KafkaTopicNames.VEHICLE_ACTIVITY), analytics.sinkTopics());
        assertTrue(analytics.stores().containsAll(List.of(StoreNames.VEHICLE_TABLE, StoreNames.ACTIVITY_WINDOWS)));
    }

    @Test
    void inShadowModeTheParityJoinReadsBothOutputsInTheSameTasks() {
        TopologyView view = StreamsIntrospection.topology(GpsStreamsTopologies.analytics(
                TopologySettings.defaults().withNormalization(StreamsNormalization.SHADOW), StreamsObserver.NONE));

        assertEquals(1, view.subtopologies().size(), "co-partitioned inputs are joined inside one task per partition");
        SubtopologyView analytics = view.subtopologies().get(0);
        assertEquals(List.of(KafkaTopicNames.PROCESSED_POSITIONS, KafkaTopicNames.SHADOW_PROCESSED_POSITIONS), analytics.sourceTopics());
        assertTrue(analytics.stores().contains(StoreNames.PARITY_TALLY));
    }
}
