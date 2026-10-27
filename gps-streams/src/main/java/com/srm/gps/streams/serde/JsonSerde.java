package com.srm.gps.streams.serde;

import com.srm.gps.configuration.json.GpsMessageJson;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;

/**
 * Kafka Streams serde for the platform's records, written with {@link GpsMessageJson} - so a processed position
 * written by Kafka Streams is byte for byte what the listener writes, and every consumer reads it unchanged.
 * State is JSON too, so a changelog topic can be read with the console consumer like any other topic.
 *
 * A record that is not valid JSON fails with a {@link SerializationException}; the streams apps are configured to
 * log and skip such records (deserialization.exception.handler) instead of stopping.
 */
public final class JsonSerde<T> implements Serde<T> {

    private final Class<T> type;

    private JsonSerde(Class<T> type) {
        this.type = type;
    }

    public static <T> JsonSerde<T> of(Class<T> type) {
        return new JsonSerde<>(type);
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> data == null ? null : GpsMessageJson.toJson(data).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return GpsMessageJson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
            } catch (GpsMessageJson.InvalidGpsMessageException invalid) {
                throw new SerializationException(invalid.getMessage(), invalid);
            }
        };
    }
}
