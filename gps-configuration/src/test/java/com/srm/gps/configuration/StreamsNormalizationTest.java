package com.srm.gps.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exactly one owner turns raw frames into positions: the listener runs unless Kafka Streams owns the job. */
class StreamsNormalizationTest {

    @Test
    void theListenerStepsAsideOnlyWhenKafkaStreamsOwnsNormalization() {
        assertFalse(StreamsNormalization.listenerNormalizes(true, "owner"));
        assertTrue(StreamsNormalization.listenerNormalizes(true, "shadow"));
        assertTrue(StreamsNormalization.listenerNormalizes(true, "off"));
        assertTrue(StreamsNormalization.listenerNormalizes(false, "owner"), "Kafka Streams switched off: the listener is the only one left");
    }

    @Test
    void anEmptySettingMeansOwnerAndCaseDoesNotMatter() {
        assertEquals(StreamsNormalization.OWNER, StreamsNormalization.fromProperty(null));
        assertEquals(StreamsNormalization.OWNER, StreamsNormalization.fromProperty(" "));
        assertEquals(StreamsNormalization.SHADOW, StreamsNormalization.fromProperty("Shadow"));
        assertFalse(StreamsNormalization.listenerNormalizes(true, null));
    }

    @Test
    void aMisspelledModeFailsAtStartInsteadOfSilentlyPickingOne() {
        assertThrows(IllegalArgumentException.class, () -> StreamsNormalization.fromProperty("both"));
    }
}
