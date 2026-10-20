package com.srm.gps.connectivity.kafka;

import org.springframework.kafka.listener.AbstractConsumerSeekAware;

/**
 * Base of every Kafka listener: lets the demo reset move all of them to the end of their topics
 * ({@link KafkaBacklogSkipper}), so messages from before the reset are never processed again.
 * The seek is performed by each listener's own consumer thread at its next poll.
 */
public abstract class SkippableListener extends AbstractConsumerSeekAware {
}
