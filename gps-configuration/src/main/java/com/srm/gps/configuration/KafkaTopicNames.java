package com.srm.gps.configuration;

/**
 * The single list of Kafka topics used by the platform (Kafka flow only).
 * Every module reads topic names from here, so a topic is never misspelled in one place only.
 *
 * Flow:  device / simulator  ->  RAW_DEVICE_FRAMES  ->  processing consumer  ->  PROCESSED_POSITIONS  ->  storage consumer
 *                                                                            ->  REJECTED_FRAMES      ->  rejected-frame logger
 */
public final class KafkaTopicNames {

    /** Frames exactly as a GPS device (or the simulator) sends them. Key = IMEI. */
    public static final String RAW_DEVICE_FRAMES = "gps.raw-device-frames";

    /** Frames that passed validation and were enriched with movement. Key = IMEI. */
    public static final String PROCESSED_POSITIONS = "gps.processed-positions";

    /** Frames the processing refused, together with the reason. Useful for debugging devices. */
    public static final String REJECTED_FRAMES = "gps.rejected-frames";

    /** Number of partitions per topic. Frames of one device always land in the same partition (keyed by IMEI). */
    public static final int PARTITIONS_PER_TOPIC = 3;

    private KafkaTopicNames() {
    }
}
