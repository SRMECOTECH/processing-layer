package com.srm.gps.streams.analytics;

import com.srm.gps.configuration.message.ProcessedGpsPosition;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Event time for the analytics: the moment the device took the reading (recordedAt), not when Kafka received it.
 * Windows, grace periods and the shadow join all run on this clock, so a reading that arrives late still lands in
 * the window it belongs to. Positions written by the listener carry the publish time as record timestamp, the
 * Kafka Streams normalizer writes recordedAt - reading it from the value treats both the same.
 *
 * The value is already deserialized when this runs. If it is not a position (a record that could not be read)
 * the record's own timestamp is used, and if that is invalid too, the partition's current stream time.
 */
public final class RecordedAtTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof ProcessedGpsPosition position && position.recordedAt() != null) {
            return position.recordedAt().toEpochMilli();
        }
        return record.timestamp() >= 0 ? record.timestamp() : Math.max(partitionTime, 0);
    }
}
