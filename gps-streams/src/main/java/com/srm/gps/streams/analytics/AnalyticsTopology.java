package com.srm.gps.streams.analytics;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.streams.StoreNames;
import com.srm.gps.streams.StreamsObserver;
import com.srm.gps.streams.TopologySettings;
import com.srm.gps.streams.serde.JsonSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.EmitStrategy;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.processor.api.RecordMetadata;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;

/**
 * The analytics app: reads the processed positions (whoever normalized them) on event time.
 *
 *   gps.processed-positions ─► observe ─► group by vehicle (key is already the IMEI: no repartition topic)
 *      (recordedAt = event time)          ├─► aggregate ───────────────────► vehicle-table-store    (KTable: latest state)
 *                                         └─► 5-min windows + grace ───────► activity-window-store  (queryable for 6 h)
 *                                              emit on window close ───────► gps.vehicle-activity    (one final record)
 *   gps.shadow.processed-positions ─► outer join on IMEI + recordedAt ─► parity-tally-store          (shadow mode only)
 *
 * Fleet-wide numbers are not a single "fleet" key - that would send every reading to one partition. They are summed
 * from the per-vehicle stores at query time, on every instance that holds a part of them.
 */
public final class AnalyticsTopology {

    private static final JsonSerde<ProcessedGpsPosition> POSITION = JsonSerde.of(ProcessedGpsPosition.class);
    private static final JsonSerde<VehicleSnapshot> SNAPSHOT = JsonSerde.of(VehicleSnapshot.class);
    private static final JsonSerde<ActivityWindow> WINDOW = JsonSerde.of(ActivityWindow.class);
    private static final JsonSerde<VehicleActivity> ACTIVITY = JsonSerde.of(VehicleActivity.class);
    private static final JsonSerde<ParityVerdict> VERDICT = JsonSerde.of(ParityVerdict.class);
    private static final JsonSerde<ParityTally> TALLY = JsonSerde.of(ParityTally.class);

    private AnalyticsTopology() {
    }

    public static void addTo(StreamsBuilder builder, TopologySettings settings, StreamsObserver observer) {
        KStream<String, ProcessedGpsPosition> positions = builder
                .stream(KafkaTopicNames.PROCESSED_POSITIONS, Consumed.with(Serdes.String(), POSITION)
                        .withTimestampExtractor(new RecordedAtTimestampExtractor()).withName("processed-positions"))
                .processValues(() -> new ObservePosition(observer), Named.as("observe-position"));

        KGroupedStream<String, ProcessedGpsPosition> byVehicle = positions.groupByKey(Grouped.with("by-vehicle", Serdes.String(), POSITION));

        // KTable: the latest known state of every vehicle
        byVehicle.aggregate(VehicleSnapshot::empty, (imei, position, snapshot) -> snapshot.fold(imei, position),
                Named.as("vehicle-table"),
                Materialized.<String, VehicleSnapshot, KeyValueStore<Bytes, byte[]>>as(StoreNames.VEHICLE_TABLE)
                        .withKeySerde(Serdes.String()).withValueSerde(SNAPSHOT));

        // Event-time windows: a reading up to windowGrace late still counts in its own window;
        // the window is published once, when stream time passes its end + grace
        byVehicle.windowedBy(TimeWindows.ofSizeAndGrace(settings.windowSize(), settings.windowGrace()))
                .emitStrategy(EmitStrategy.onWindowClose())
                .aggregate(ActivityWindow::empty, (imei, position, window) -> window.add(position),
                        Named.as("activity-window"),
                        Materialized.<String, ActivityWindow, WindowStore<Bytes, byte[]>>as(StoreNames.ACTIVITY_WINDOWS)
                                .withKeySerde(Serdes.String()).withValueSerde(WINDOW).withRetention(settings.windowRetention()))
                .toStream(Named.as("closed-windows"))
                .map((window, activity) -> KeyValue.pair(window.key(), VehicleActivity.of(window.key(), window.window(), activity)),
                        Named.as("activity-record"))
                .to(KafkaTopicNames.VEHICLE_ACTIVITY, Produced.with(Serdes.String(), ACTIVITY).withName("vehicle-activity-out"));

        if (settings.normalization() == StreamsNormalization.SHADOW) {
            addParity(builder, positions, settings.parityGrace());
        }
    }

    /**
     * Both topics are keyed by IMEI with the same partition count (co-partitioned), and both sides carry recordedAt
     * as timestamp - so a join window of 0 pairs exactly the two results for the same reading. An outer join emits
     * the unpaired ones too, once the window plus grace has closed: "only one side produced this reading".
     */
    private static void addParity(StreamsBuilder builder, KStream<String, ProcessedGpsPosition> listener, Duration grace) {
        KStream<String, ProcessedGpsPosition> streams = builder.stream(KafkaTopicNames.SHADOW_PROCESSED_POSITIONS,
                Consumed.with(Serdes.String(), POSITION).withTimestampExtractor(new RecordedAtTimestampExtractor())
                        .withName("shadow-processed-positions"));

        listener.outerJoin(streams, ParityVerdict::compare, JoinWindows.ofTimeDifferenceAndGrace(Duration.ZERO, grace),
                        StreamJoined.with(Serdes.String(), POSITION, POSITION).withName("parity-join").withStoreName("parity-join"))
                .groupByKey(Grouped.with("parity-by-vehicle", Serdes.String(), VERDICT))
                .aggregate(ParityTally::empty, (imei, verdict, tally) -> tally.add(verdict), Named.as("parity-tally"),
                        Materialized.<String, ParityTally, KeyValueStore<Bytes, byte[]>>as(StoreNames.PARITY_TALLY)
                                .withKeySerde(Serdes.String()).withValueSerde(TALLY));
    }

    /** Passes every position on unchanged and tells the observer (with its partition) - key untouched, so no repartition. */
    private static final class ObservePosition implements FixedKeyProcessor<String, ProcessedGpsPosition, ProcessedGpsPosition> {

        private final StreamsObserver observer;
        private FixedKeyProcessorContext<String, ProcessedGpsPosition> context;

        private ObservePosition(StreamsObserver observer) {
            this.observer = observer;
        }

        @Override
        public void init(FixedKeyProcessorContext<String, ProcessedGpsPosition> context) {
            this.context = context;
        }

        @Override
        public void process(FixedKeyRecord<String, ProcessedGpsPosition> record) {
            observer.positionAnalyzed(record.key(), context.recordMetadata().map(RecordMetadata::partition).orElse(-1));
            context.forward(record);
        }
    }
}
