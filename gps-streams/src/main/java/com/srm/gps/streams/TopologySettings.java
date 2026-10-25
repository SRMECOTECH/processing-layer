package com.srm.gps.streams;

import com.srm.gps.configuration.StreamsNormalization;
import com.srm.gps.processing.step.DistanceSettings;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything that shapes the two topologies. Changing a window size or a store retention changes stored state,
 * so treat these like a schema: change them together with a reset of the streams apps.
 *
 * @param normalization         owner (real topics), shadow (gps.shadow.* + parity) or off (no normalizer)
 * @param distance              the same distance settings as the listener (gps.distance.*)
 * @param maxLateness           a reading older than this behind its device's newest reading is rejected as stale
 * @param seenReadingsRetention how long accepted readings are remembered for duplicate detection
 * @param windowSize            activity window size (event time)
 * @param windowGrace           how late a reading may arrive after its window ended and still be counted
 * @param windowRetention       how long closed windows stay queryable (Interactive Queries)
 * @param parityGrace           how long one side of the shadow comparison waits for the other side
 */
public record TopologySettings(
        StreamsNormalization normalization,
        DistanceSettings distance,
        Duration maxLateness,
        Duration seenReadingsRetention,
        Duration windowSize,
        Duration windowGrace,
        Duration windowRetention,
        Duration parityGrace) {

    public TopologySettings {
        Objects.requireNonNull(normalization, "normalization");
        Objects.requireNonNull(distance, "distance");
        requirePositive(maxLateness, "maxLateness");
        requirePositive(seenReadingsRetention, "seenReadingsRetention");
        requirePositive(windowSize, "windowSize");
        Objects.requireNonNull(windowGrace, "windowGrace");
        Objects.requireNonNull(parityGrace, "parityGrace");
        if (windowRetention.compareTo(windowSize.plus(windowGrace)) < 0) {
            // Kafka Streams refuses a window store that forgets a window before it can close
            throw new IllegalArgumentException("windowRetention must be at least windowSize + windowGrace");
        }
    }

    public static TopologySettings defaults() {
        return new TopologySettings(StreamsNormalization.OWNER, DistanceSettings.defaults(), Duration.ofHours(24),
                Duration.ofHours(24), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(6), Duration.ofMinutes(2));
    }

    public TopologySettings withNormalization(StreamsNormalization mode) {
        return new TopologySettings(mode, distance, maxLateness, seenReadingsRetention, windowSize, windowGrace,
                windowRetention, parityGrace);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
