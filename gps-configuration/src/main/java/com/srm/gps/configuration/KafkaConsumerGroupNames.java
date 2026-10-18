package com.srm.gps.configuration;

/**
 * The single list of Kafka consumer groups.
 *
 * One group = one PURPOSE. Every group receives every message of its topic and keeps its own bookmark (offset).
 * Inside one group, Kafka gives each partition to exactly one consumer, so at most
 * {@link KafkaTopicNames#PARTITIONS_PER_TOPIC} consumers of a group can do work at the same time.
 */
public final class KafkaConsumerGroupNames {

    /** Reads raw frames, validates them, calculates movement, writes processed positions or rejections. */
    public static final String RAW_FRAME_PROCESSING = "gps-raw-frame-processing-service";

    /** Reads processed positions and stores them in PostgreSQL. */
    public static final String POSITION_STORAGE = "gps-position-storage-service";

    /** Reads processed positions and keeps the latest position of every vehicle in Redis. */
    public static final String LIVE_LOCATION = "gps-live-location-service";

    /** Reads rejected frames and writes them to the service log. */
    public static final String REJECTED_FRAME_LOGGER = "gps-rejected-frame-logger";

    /** One consumer thread per partition - the most a group can use. Text, because Spring's listener setting is text. */
    public static final String ONE_CONSUMER_PER_PARTITION = "" + KafkaTopicNames.PARTITIONS_PER_TOPIC;

    private KafkaConsumerGroupNames() {
    }
}
