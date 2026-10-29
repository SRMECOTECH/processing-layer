package com.srm.gps.configuration;

/**
 * The single list of Kafka consumer groups.
 *
 * One group = one PURPOSE. Every group receives every message of its topic and keeps its own bookmark (offset).
 * Inside one group, Kafka gives each partition to exactly one consumer, so at most
 * {@link KafkaTopicNames#PARTITIONS_PER_TOPIC} consumers of a group can do work at the same time.
 */
public final class KafkaConsumerGroupNames {

    /**
     * Reads raw frames, validates them, calculates movement, writes processed positions or rejections.
     * It is also the application.id of the Kafka Streams normalizer: a Kafka Streams application.id IS its consumer
     * group, so switching between the listener and Kafka Streams continues from the same committed offsets -
     * no replay and no gap in either direction (the two never run at the same time).
     */
    public static final String RAW_FRAME_PROCESSING = "gps-raw-frame-processing-service";

    /** Kafka Streams normalizer in shadow mode: same logic as the owner, writing to the gps.shadow.* topics. */
    public static final String STREAMS_SHADOW_NORMALIZER = "gps-streams-shadow-normalizer";

    /** Kafka Streams analytics: vehicle table, 5-minute activity windows, shadow parity. Its application.id. */
    public static final String STREAMS_ANALYTICS = "gps-streams-analytics";

    /** Reads processed positions and stores them in PostgreSQL. */
    public static final String POSITION_STORAGE = "gps-position-storage-service";

    /** Reads processed positions and keeps the latest position of every vehicle in Redis. */
    public static final String LIVE_LOCATION = "gps-live-location-service";

    /** Reads processed positions, detects trips and stops, and keeps them in PostgreSQL. */
    public static final String TRIP_DETECTION = "gps-trip-service";

    /** Reads processed positions and checks the entity's speed rule against speedometer and GPS. */
    public static final String SPEED_VIOLATION = "gps-speed-violation-service";

    /** Reads processed positions and keeps each vehicle's full state and recent history in Redis. */
    public static final String VEHICLE_STATE = "gps-vehicle-state-service";

    /** Reads processed positions and detects stoppages (engine on) and parking (engine off). */
    public static final String STOPPAGE = "gps-stoppage-service";

    /** Reads processed positions and tracks ignition, engine hours and idling. */
    public static final String IGNITION = "gps-ignition-service";

    /** Reads rejected frames, archives them in PostgreSQL (rejected_frame) and writes them to the service log. */
    public static final String REJECTED_FRAME_LOGGER = "gps-rejected-frame-logger";

    /** Reads dead letters and archives them in PostgreSQL (dead_letter), where they are kept and can be replayed. */
    public static final String DEAD_LETTER_ARCHIVE = "gps-dead-letter-archive";

    /** One consumer thread per partition - the most a group can use. Text, because Spring's listener setting is text. */
    public static final String ONE_CONSUMER_PER_PARTITION = "" + KafkaTopicNames.PARTITIONS_PER_TOPIC;

    private KafkaConsumerGroupNames() {
    }
}
