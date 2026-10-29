package com.srm.gps.streams.normalize;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.processing.ProcessingResult;
import com.srm.gps.processing.step.PreviousReading;
import com.srm.gps.streams.StoreNames;
import com.srm.gps.streams.StreamsObserver;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.serde.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.Stores;

import java.time.Duration;

/**
 * The normalizer app: the boundary where raw device frames become trusted positions.
 *
 *   gps.raw-device-frames ──► normalize-frame ──┬─ accepted ──► gps.processed-positions   (key IMEI, event-time stamped)
 *     (key IMEI, 3 partitions)    │             └─ rejected ──► gps.rejected-frames       (key IMEI or none)
 *                       previous-reading-store
 *                       seen-reading-store
 *
 * One sub-topology, so one task per input partition (0_0, 0_1, 0_2): a task owns its partition's devices and their
 * state - the unit of parallelism and of fail-over. In shadow mode the sinks are the gps.shadow.* topics instead.
 */
public final class NormalizationTopology {

    private static final JsonSerde<ProcessedGpsPosition> POSITION = JsonSerde.of(ProcessedGpsPosition.class);
    private static final JsonSerde<RejectedGpsFrame> REJECTION = JsonSerde.of(RejectedGpsFrame.class);

    private NormalizationTopology() {
    }

    public static void addTo(StreamsBuilder builder, TopologySettings settings, PreviousReadingFallback fallback,
                             StreamsObserver observer) {
        boolean shadow = settings.normalization() == StreamsNormalization.SHADOW;
        String processedTopic = shadow ? KafkaTopicNames.SHADOW_PROCESSED_POSITIONS : KafkaTopicNames.PROCESSED_POSITIONS;
        String rejectedTopic = shadow ? KafkaTopicNames.SHADOW_REJECTED_FRAMES : KafkaTopicNames.REJECTED_FRAMES;
        String logStage = shadow ? "streams-shadow-normalizer" : PipelineStage.RAW_FRAME_PROCESSING.shortName();

        builder.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(StoreNames.PREVIOUS_READINGS), Serdes.String(), JsonSerde.of(PreviousReading.class)));
        // window start = the reading's recordedAt; a 1 ms window per reading, kept for seenReadingsRetention
        builder.addStateStore(Stores.windowStoreBuilder(
                Stores.persistentWindowStore(StoreNames.SEEN_READINGS, settings.seenReadingsRetention(), Duration.ofMillis(1), false),
                Serdes.String(), Serdes.Long()));

        KStream<String, ProcessingResult> results = builder
                .stream(KafkaTopicNames.RAW_DEVICE_FRAMES, Consumed.with(Serdes.String(), Serdes.String()).withName("raw-device-frames"))
                .process(() -> new NormalizeFrameProcessor(settings.distance(), settings.maxLateness(), fallback, observer, logStage),
                        Named.as("normalize-frame"), StoreNames.PREVIOUS_READINGS, StoreNames.SEEN_READINGS);

        results.split(Named.as("result-"))
                .branch((imei, result) -> result instanceof ProcessingResult.Accepted, Branched.withConsumer(accepted -> accepted
                        .mapValues(result -> ((ProcessingResult.Accepted) result).position(), Named.as("accepted-position"))
                        .to(processedTopic, Produced.with(Serdes.String(), POSITION).withName("processed-positions-out")), "accepted"))
                .defaultBranch(Branched.withConsumer(rejected -> rejected
                        .mapValues(result -> ((ProcessingResult.Rejected) result).rejection(), Named.as("rejection"))
                        .to(rejectedTopic, Produced.with(Serdes.String(), REJECTION).withName("rejected-frames-out")), "rejected"));
    }
}
