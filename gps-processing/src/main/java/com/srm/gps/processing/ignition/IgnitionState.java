package com.srm.gps.processing.ignition;

import java.time.Instant;

/**
 * Everything the ignition tracker remembers about one device (Redis gps:state:ignition:{imei}).
 *
 * @param ignitionOn       true / false, null until known
 * @param source           DEVICE (ignition wire) or VIRTUAL (inferred from motion)
 * @param changedAt        when ignition last changed
 * @param pendingValue     a change seen but not yet confirmed (debounce), with how many readings and since when
 * @param engineSeconds    engine running time (Traccar "hours", in seconds)
 * @param idleSince        start of the current idling, null when not idling
 * @param idleAlerted      IDLING already raised for the current idling
 * @param idleSecondsTotal all completed idling
 * @param standingSince    start of the current standing (for virtual ignition)
 */
public record IgnitionState(
        Boolean ignitionOn,
        String source,
        Instant changedAt,
        Boolean pendingValue,
        int pendingReadings,
        Instant pendingSince,
        long engineSeconds,
        Instant idleSince,
        boolean idleAlerted,
        long idleSecondsTotal,
        Instant standingSince,
        Instant lastRecordedAt,
        double lastLatitude,
        double lastLongitude,
        long ignitionOnCount) {

    public static final String DEVICE = "DEVICE";
    public static final String VIRTUAL = "VIRTUAL";

    public static IgnitionState initial() {
        return new IgnitionState(null, null, null, null, 0, null, 0, null, false, 0, null, null, 0, 0, 0);
    }
}
