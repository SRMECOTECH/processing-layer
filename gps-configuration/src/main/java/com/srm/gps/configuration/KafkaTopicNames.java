package com.srm.gps.configuration;

/**
 * The single list of Kafka topics used by the platform (Kafka flow only).
 * Every module reads topic names from here, so a topic is never misspelled in one place only.
 *
 * Flow:  device / simulator  ->  RAW_DEVICE_FRAMES  ->  normalizer  ->  PROCESSED_POSITIONS  ->  storage, live location, trips ...
 *                                                                   ->  REJECTED_FRAMES      ->  rejected-frame logger
 *                                                   PROCESSED_POSITIONS  ->  Kafka Streams analytics  ->  VEHICLE_ACTIVITY
 *
 * The normalizer is Kafka Streams (gps.streams.normalization=owner) or the listener; in shadow mode the listener
 * owns the real topics and Kafka Streams writes the same results to the SHADOW_* topics, so the two can be compared.
 */
public final class KafkaTopicNames {

    /** Frames exactly as a GPS device (or the simulator) sends them. Key = IMEI. */
    public static final String RAW_DEVICE_FRAMES = "gps.raw-device-frames";

    /** Frames that passed validation and were enriched with movement. Key = IMEI. */
    public static final String PROCESSED_POSITIONS = "gps.processed-positions";

    /** Frames the processing refused, together with the reason. Useful for debugging devices. */
    public static final String REJECTED_FRAMES = "gps.rejected-frames";

    /** Events the stream consumers raise (over-speed, stoppage, ignition ...). Key = IMEI. */
    public static final String VEHICLE_EVENTS = "gps.vehicle-events";

    /** Shadow run: what the Kafka Streams normalizer would have published, compared reading by reading. Key = IMEI. */
    public static final String SHADOW_PROCESSED_POSITIONS = "gps.shadow.processed-positions";

    /** Shadow run: what the Kafka Streams normalizer would have rejected. */
    public static final String SHADOW_REJECTED_FRAMES = "gps.shadow.rejected-frames";

    /**
     * Each vehicle's activity per 5-minute event-time window (readings, speed, distance), published once the window
     * and its grace period have closed - one final record per vehicle and window. Key = IMEI.
     */
    public static final String VEHICLE_ACTIVITY = "gps.vehicle-activity";

    /** Number of partitions per topic. Frames of one device always land in the same partition (keyed by IMEI). */
    public static final int PARTITIONS_PER_TOPIC = 3;

    private KafkaTopicNames() {
    }
}
