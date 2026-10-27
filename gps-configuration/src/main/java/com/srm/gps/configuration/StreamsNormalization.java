package com.srm.gps.configuration;

import java.util.Locale;

/**
 * Who turns raw frames into processed positions in the Kafka flow - exactly ONE owner at a time.
 * Chosen with {@value #PROPERTY} (and Kafka Streams as a whole with {@value #ENABLED_PROPERTY}).
 *
 *   OWNER  : Kafka Streams normalizes (exactly-once: outputs, state and offsets commit together); the listener is off.
 *   SHADOW : the listener normalizes; Kafka Streams runs the same logic into gps.shadow.* and the parity report
 *            compares both outputs reading by reading - the way to validate it before handing over.
 *   OFF    : the listener normalizes; Kafka Streams only runs the analytics.
 *
 * With Kafka Streams switched off entirely the listener normalizes, as before.
 */
public enum StreamsNormalization {

    OWNER,
    SHADOW,
    OFF;

    public static final String ENABLED_PROPERTY = "gps.streams.enabled";
    public static final String PROPERTY = "gps.streams.normalization";

    /** Reads the property value; empty means the default, OWNER. */
    public static StreamsNormalization fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return OWNER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(PROPERTY + " must be owner, shadow or off, not '" + value + "'", unknown);
        }
    }

    /** Whether the @KafkaListener consumer must run: everywhere except when Kafka Streams owns the job. */
    public static boolean listenerNormalizes(boolean streamsEnabled, String normalization) {
        return !streamsEnabled || fromProperty(normalization) != OWNER;
    }

    /** Lower-case, as written in application.yml. */
    public String propertyValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
