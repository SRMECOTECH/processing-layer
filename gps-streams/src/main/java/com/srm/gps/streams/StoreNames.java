package com.srm.gps.streams;

/**
 * The state stores of the two Kafka Streams apps. Each is local RocksDB, one copy per task (= per input partition),
 * backed by a compacted changelog topic named {application.id}-{store}-changelog, from which a task's state is
 * restored when it moves to another instance or the local copy is lost.
 */
public final class StoreNames {

    /** Normalizer, key-value, IMEI -> the newest accepted reading: what the next reading is measured from. */
    public static final String PREVIOUS_READINGS = "previous-reading-store";

    /**
     * Normalizer, window store, IMEI + recordedAt -> when it was first seen: every reading accepted in the last
     * day, so a device re-sending an OLDER reading is caught as a duplicate too, not only a repeat of the newest.
     */
    public static final String SEEN_READINGS = "seen-reading-store";

    /** Analytics, KTable, IMEI -> latest known state of the vehicle (newest by event time) and its counters. */
    public static final String VEHICLE_TABLE = "vehicle-table-store";

    /** Analytics, windowed, IMEI + 5-minute event-time window -> readings, speed, distance in that window. */
    public static final String ACTIVITY_WINDOWS = "activity-window-store";

    /** Analytics (shadow mode), KTable, IMEI -> how the listener's and Kafka Streams' outputs compared. */
    public static final String PARITY_TALLY = "parity-tally-store";

    private StoreNames() {
    }
}
