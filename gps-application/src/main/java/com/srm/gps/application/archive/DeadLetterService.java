package com.srm.gps.application.archive;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The dead-letter archive as an operator sees it: what is parked, why, and the two ways out - replay (send the record
 * to its original topic again, after the cause was fixed) or discard (with a note). Rows are never deleted.
 *
 * Replay is confirmed by Kafka before the row says REPLAYED; if Kafka does not confirm, the row stays PARKED.
 * A replayed record reaches every consumer group of its topic again, not only the one that failed: they are all
 * idempotent (storage by IMEI + recordedAt, trips by guarded state, the normalizer's duplicate store).
 */
@Service
public class DeadLetterService {

    public static final String REPLAY_HEADER = "gps-replay-of-dead-letter";

    public record DeadLetterRow(long id, String source, String originalTopic, Integer originalPartition, Long originalOffset,
                                String consumerGroup, String recordKey, String recordValue, String exceptionClass,
                                String exceptionMessage, String stacktrace, Instant parkedAt, String status, int replayCount,
                                Instant lastReplayedAt, String resolutionNote) {
    }

    public record CountBy(String name, long parked, long total) {
    }

    public record Summary(long parked, long replayed, long discarded, Instant oldestParkedAt, List<CountBy> byTopic,
                          List<CountBy> byException) {
    }

    public record ReplayReport(int replayed, List<String> problems) {
    }

    /** Thrown when an action does not apply to a dead letter in its state (HTTP 409). */
    public static class NotPossibleException extends RuntimeException {
        public NotPossibleException(String message) {
            super(message);
        }
    }

    private static final String COLUMNS = """
            id, source, original_topic, original_partition, original_offset, consumer_group, record_key, record_value,
            exception_class, exception_message, stacktrace, parked_at, status, replay_count, last_replayed_at, resolution_note
            """;

    private static final RowMapper<DeadLetterRow> ROW = (rs, n) -> new DeadLetterRow(rs.getLong("id"), rs.getString("source"),
            rs.getString("original_topic"), (Integer) rs.getObject("original_partition"), (Long) rs.getObject("original_offset"),
            rs.getString("consumer_group"), rs.getString("record_key"), rs.getString("record_value"),
            rs.getString("exception_class"), rs.getString("exception_message"), rs.getString("stacktrace"),
            rs.getTimestamp("parked_at").toInstant(), rs.getString("status"), rs.getInt("replay_count"),
            rs.getTimestamp("last_replayed_at") == null ? null : rs.getTimestamp("last_replayed_at").toInstant(),
            rs.getString("resolution_note"));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate;
    private final TransactionTemplate transaction;

    public DeadLetterService(NamedParameterJdbcTemplate jdbc, ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
                             TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.transaction = transaction;
    }

    /** Newest first; the list leaves the stack trace out (see {@link #find}). */
    public List<DeadLetterRow> list(String status, String topic, int limit) {
        List<DeadLetterRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM dead_letter"
                        + " WHERE (CAST(:status AS varchar) IS NULL OR status = :status)"
                        + " AND (CAST(:topic AS varchar) IS NULL OR original_topic = :topic)"
                        + " ORDER BY parked_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource().addValue("status", blankToNull(status)).addValue("topic", blankToNull(topic))
                        .addValue("limit", limit), ROW);
        return rows.stream().map(r -> new DeadLetterRow(r.id(), r.source(), r.originalTopic(), r.originalPartition(),
                r.originalOffset(), r.consumerGroup(), r.recordKey(), r.recordValue(), r.exceptionClass(),
                r.exceptionMessage(), null, r.parkedAt(), r.status(), r.replayCount(), r.lastReplayedAt(), r.resolutionNote())).toList();
    }

    public DeadLetterRow find(long id) {
        List<DeadLetterRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM dead_letter WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW);
        if (rows.isEmpty()) {
            throw new NotPossibleException("No dead letter " + id);
        }
        return rows.get(0);
    }

    public Summary summary() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        long parked = count("PARKED");
        long replayed = count("REPLAYED");
        long discarded = count("DISCARDED");
        Timestamp oldest = jdbc.queryForObject("SELECT min(parked_at) FROM dead_letter WHERE status = 'PARKED'", none, Timestamp.class);
        RowMapper<CountBy> countBy = (rs, n) -> new CountBy(rs.getString("name"), rs.getLong("parked"), rs.getLong("total"));
        List<CountBy> byTopic = jdbc.query("""
                SELECT coalesce(original_topic, '(unknown)') AS name, count(*) FILTER (WHERE status = 'PARKED') AS parked,
                       count(*) AS total FROM dead_letter GROUP BY 1 ORDER BY 2 DESC, 3 DESC LIMIT 20""", none, countBy);
        List<CountBy> byException = jdbc.query("""
                SELECT coalesce(exception_class, '(unknown)') AS name, count(*) FILTER (WHERE status = 'PARKED') AS parked,
                       count(*) AS total FROM dead_letter GROUP BY 1 ORDER BY 2 DESC, 3 DESC LIMIT 20""", none, countBy);
        return new Summary(parked, replayed, discarded, oldest == null ? null : oldest.toInstant(), byTopic, byException);
    }

    /**
     * Sends the record to its original topic again, confirmed by Kafka, then marks it REPLAYED - with the row locked
     * meanwhile, so two operators replaying the same dead letter do not both send it.
     */
    public DeadLetterRow replay(long id) {
        transaction.executeWithoutResult(status -> replayLocked(id));
        return find(id);
    }

    private void replayLocked(long id) {
        KafkaTemplate<String, String> kafka = kafkaTemplate.getIfAvailable();
        if (kafka == null) {
            throw new NotPossibleException("Replay needs Kafka: the direct flow has no topics to replay into");
        }
        DeadLetterRow row = jdbc.query("SELECT " + COLUMNS + " FROM dead_letter WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), ROW).stream().findFirst()
                .orElseThrow(() -> new NotPossibleException("No dead letter " + id));
        if ("DISCARDED".equals(row.status())) {
            throw new NotPossibleException("Dead letter " + id + " was discarded: " + row.resolutionNote());
        }
        if (row.originalTopic() == null) {
            throw new NotPossibleException("Dead letter " + id + " does not say which topic it came from");
        }
        RecordHeaders headers = new RecordHeaders();
        headers.add(REPLAY_HEADER, Long.toString(id).getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(new ProducerRecord<>(row.originalTopic(), null, row.recordKey(), row.recordValue(), headers))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception notConfirmed) {
            throw new NotPossibleException("Kafka did not confirm the replay; the dead letter stays parked: " + notConfirmed.getMessage());
        }
        jdbc.update("UPDATE dead_letter SET status = 'REPLAYED', replay_count = replay_count + 1, last_replayed_at = now()"
                + " WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    /** Replays the oldest parked dead letters (of one topic, or all), one by one; one that fails stays parked. */
    public ReplayReport replayParked(String topic, int limit) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM dead_letter WHERE status = 'PARKED'"
                        + " AND (CAST(:topic AS varchar) IS NULL OR original_topic = :topic) ORDER BY parked_at, id LIMIT :limit",
                new MapSqlParameterSource().addValue("topic", blankToNull(topic)).addValue("limit", limit), Long.class);
        int replayed = 0;
        List<String> problems = new ArrayList<>();
        for (long id : ids) {
            try {
                replay(id);
                replayed++;
            } catch (RuntimeException problem) {
                problems.add(id + ": " + problem.getMessage());
            }
        }
        return new ReplayReport(replayed, problems);
    }

    /** Marks a dead letter as not to be replayed, with the reason. The row and the record stay. */
    public DeadLetterRow discard(long id, String note) {
        if (note == null || note.isBlank()) {
            throw new NotPossibleException("Say why it is discarded: the note is kept with the record");
        }
        int changed = jdbc.update("UPDATE dead_letter SET status = 'DISCARDED', resolution_note = :note WHERE id = :id AND status = 'PARKED'",
                new MapSqlParameterSource().addValue("id", id).addValue("note", ArchiveText.text(note.trim())));
        if (changed == 0) {
            throw new NotPossibleException("Only a parked dead letter can be discarded");
        }
        return find(id);
    }

    private long count(String status) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM dead_letter WHERE status = :status",
                new MapSqlParameterSource("status", status), Long.class);
        return count == null ? 0 : count;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
