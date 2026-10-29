package com.srm.gps.application.archive;

import com.srm.gps.connectivity.kafka.DeadLetter;
import com.srm.gps.connectivity.kafka.DeadLetterHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * Keeps every dead letter in PostgreSQL (dead_letter), beyond Kafka's one-month retention.
 *
 * Idempotent: the same dead letter delivered twice is one row (dead-letter partition + offset + time). Text is made
 * storable first (no NUL, varchar lengths respected), so a record can never be refused by the database - the only
 * failure left is the database being down, and that is retried until it is back.
 */
@Service
public class DeadLetterArchive implements DeadLetterHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterArchive.class);

    private static final String INSERT = """
            INSERT INTO dead_letter (source, original_topic, original_partition, original_offset, consumer_group,
                record_key, record_value, exception_class, exception_message, stacktrace, parked_at,
                dead_letter_partition, dead_letter_offset)
            VALUES (:source, :originalTopic, :originalPartition, :originalOffset, :consumerGroup,
                :key, :value, :exceptionClass, :exceptionMessage, :stacktrace, :parkedAt,
                :deadLetterPartition, :deadLetterOffset)
            ON CONFLICT ON CONSTRAINT dead_letter_archived_once DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeadLetterArchive(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void handle(DeadLetter deadLetter) {
        int stored = jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("source", deadLetter.source())
                .addValue("originalTopic", ArchiveText.limited(deadLetter.originalTopic(), 250))
                .addValue("originalPartition", deadLetter.originalPartition())
                .addValue("originalOffset", deadLetter.originalOffset())
                .addValue("consumerGroup", ArchiveText.limited(deadLetter.consumerGroup(), 250))
                .addValue("key", ArchiveText.limited(deadLetter.key(), 250))
                .addValue("value", ArchiveText.text(deadLetter.value()))
                .addValue("exceptionClass", ArchiveText.limited(deadLetter.exceptionClass(), 500))
                .addValue("exceptionMessage", ArchiveText.text(deadLetter.exceptionMessage()))
                .addValue("stacktrace", ArchiveText.text(deadLetter.stacktrace()))
                .addValue("parkedAt", Timestamp.from(deadLetter.parkedAt()))
                .addValue("deadLetterPartition", deadLetter.deadLetterPartition())
                .addValue("deadLetterOffset", deadLetter.deadLetterOffset()));
        if (stored > 0) {
            LOG.warn("Dead letter archived: {} {}-{}@{} ({}): {}", deadLetter.source(), deadLetter.originalTopic(),
                    deadLetter.originalPartition(), deadLetter.originalOffset(), deadLetter.exceptionClass(), deadLetter.exceptionMessage());
        }
    }
}
