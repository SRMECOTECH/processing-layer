package com.srm.gps.configuration;

/**
 * Every place a message passes through, with a plain-English label.
 * The engine dashboard, the log lines and the Redis load counters all use these names,
 * so "how much did the storage consumer eat for IMEI X?" has one answer everywhere.
 */
public enum PipelineStage {

    RAW_FRAME_PRODUCER(Kind.PRODUCER, "Producer 1: device frames (simulator, later the parser)",
            KafkaTopicNames.RAW_DEVICE_FRAMES, null),
    PROCESSED_POSITION_PRODUCER(Kind.PRODUCER, "Producer 2: processed positions",
            KafkaTopicNames.PROCESSED_POSITIONS, null),
    REJECTED_FRAME_PRODUCER(Kind.PRODUCER, "Producer 3: rejected frames",
            KafkaTopicNames.REJECTED_FRAMES, null),

    RAW_FRAME_PROCESSING(Kind.CONSUMER, "Validate frame + calculate movement",
            KafkaTopicNames.RAW_DEVICE_FRAMES, KafkaConsumerGroupNames.RAW_FRAME_PROCESSING),
    POSITION_STORAGE(Kind.CONSUMER, "Store position in PostgreSQL",
            KafkaTopicNames.PROCESSED_POSITIONS, KafkaConsumerGroupNames.POSITION_STORAGE),
    LIVE_LOCATION(Kind.CONSUMER, "Keep latest position per vehicle in Redis",
            KafkaTopicNames.PROCESSED_POSITIONS, KafkaConsumerGroupNames.LIVE_LOCATION),
    TRIP_DETECTION(Kind.CONSUMER, "Detect trips and stops, keep them in PostgreSQL",
            KafkaTopicNames.PROCESSED_POSITIONS, KafkaConsumerGroupNames.TRIP_DETECTION),
    REJECTED_FRAME_LOGGER(Kind.CONSUMER, "Log rejected frames with the reason",
            KafkaTopicNames.REJECTED_FRAMES, KafkaConsumerGroupNames.REJECTED_FRAME_LOGGER),

    DIRECT_PROCESSING(Kind.DIRECT, "Direct flow: process, store, update live location and trips in one call",
            null, null);

    public enum Kind { PRODUCER, CONSUMER, DIRECT }

    private final Kind kind;
    private final String label;
    private final String topic;
    private final String consumerGroup;

    PipelineStage(Kind kind, String label, String topic, String consumerGroup) {
        this.kind = kind;
        this.label = label;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
    }

    public Kind kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    /** The topic written to (producer) or read from (consumer); null for the direct flow. */
    public String topic() {
        return topic;
    }

    /** The consumer group; null for producers and the direct flow. */
    public String consumerGroup() {
        return consumerGroup;
    }

    /** Short lower-case name for logs and Redis keys, for example "position-storage". */
    public String shortName() {
        return name().toLowerCase().replace('_', '-');
    }
}
