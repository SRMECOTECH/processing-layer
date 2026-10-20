package com.srm.gps.processing.ignition;

import java.time.Instant;

/**
 * An ignition change or an idling episode.
 *
 * @param kind              IGNITION_ON, IGNITION_OFF, IDLING_STARTED or IDLING_ENDED
 * @param startedAt         when it happened (a confirmed change is back-dated to its first reading)
 * @param endedAt           for IDLING_ENDED, the end of idling
 * @param source            DEVICE or VIRTUAL
 * @param previousSeconds   for ignition changes: how long the previous state lasted
 */
public record IgnitionFinding(Kind kind, Instant startedAt, Instant endedAt, double latitude, double longitude,
                              String source, long previousSeconds, long durationSeconds) {

    public enum Kind { IGNITION_ON, IGNITION_OFF, IDLING_STARTED, IDLING_ENDED }
}
