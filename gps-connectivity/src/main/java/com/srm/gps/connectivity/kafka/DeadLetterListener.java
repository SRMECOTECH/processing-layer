package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group "gps-dead-letter-archive": every dead letter, from the listeners and from Kafka Streams, goes to the
 * {@link DeadLetterHandler} (PostgreSQL dead_letter). Its own failures are retried until the archive has the record -
 * a dead letter is never parked a second time. (Skippable only for the demo reset, which empties the archive too.)
 */
@Component
@OnlyInKafkaFlow
public class DeadLetterListener extends SkippableListener {

    private static final PipelineStage STAGE = PipelineStage.DEAD_LETTER_ARCHIVE;

    private final DeadLetterHandler handler;
    private final PipelineMetrics metrics;

    public DeadLetterListener(DeadLetterHandler handler, PipelineMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaTopicNames.DEAD_LETTERS, groupId = KafkaConsumerGroupNames.DEAD_LETTER_ARCHIVE)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        try (PipelineLogContext ignored = PipelineLogContext.forRecord(STAGE, record)) {
            handler.handle(DeadLetter.from(record));
            metrics.recordMessage(STAGE, record.key(), record.partition());
        } catch (RuntimeException failure) {
            metrics.recordFailure(STAGE);
            throw failure;
        }
    }
}
