package com.srm.gps.application.archive;

import com.srm.gps.configuration.message.RejectedGpsFrame;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Rejected frames in PostgreSQL (rejected_frame): the payload exactly as it arrived, the reason and the moment.
 * A redelivered rejection (same payload, same moment) is stored once.
 */
@Service
public class RejectedFrameArchive {

    public record RejectedFrameRow(long id, String imei, String rejectionReason, String originalPayload, Instant rejectedAt) {
    }

    /** @param reason the reason up to its first ':' ("Duplicate reading", "Latitude 95.0 is outside -90..90" ...) */
    public record ReasonCount(String reason, long lastDay, long total) {
    }

    public record Summary(long total, long lastDay, Instant newest, List<ReasonCount> byReason) {
    }

    private static final String INSERT = """
            INSERT INTO rejected_frame (imei, rejection_reason, original_payload, rejected_at, payload_hash)
            VALUES (:imei, :reason, :payload, :rejectedAt, md5(:payload))
            ON CONFLICT ON CONSTRAINT rejected_frame_archived_once DO NOTHING
            """;

    private static final RowMapper<RejectedFrameRow> ROW = (rs, n) -> new RejectedFrameRow(rs.getLong("id"), rs.getString("imei"),
            rs.getString("rejection_reason"), rs.getString("original_payload"), rs.getTimestamp("rejected_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public RejectedFrameArchive(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void store(RejectedGpsFrame rejection) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("imei", ArchiveText.limited(rejection.imei(), 50))
                .addValue("reason", ArchiveText.text(rejection.rejectionReason() == null ? "(no reason)" : rejection.rejectionReason()))
                .addValue("payload", ArchiveText.text(rejection.originalPayload() == null ? "" : rejection.originalPayload()))
                .addValue("rejectedAt", Timestamp.from(rejection.rejectedAt() != null ? rejection.rejectedAt() : Instant.now())));
    }

    public List<RejectedFrameRow> list(String imei, int limit) {
        return jdbc.query("""
                SELECT id, imei, rejection_reason, original_payload, rejected_at FROM rejected_frame
                WHERE (CAST(:imei AS varchar) IS NULL OR imei = :imei)
                ORDER BY rejected_at DESC, id DESC LIMIT :limit""",
                new MapSqlParameterSource().addValue("imei", imei == null || imei.isBlank() ? null : imei.trim()).addValue("limit", limit), ROW);
    }

    public Summary summary() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) FROM rejected_frame", none, Long.class);
        Long lastDay = jdbc.queryForObject("SELECT count(*) FROM rejected_frame WHERE rejected_at > now() - interval '24 hours'", none, Long.class);
        Timestamp newest = jdbc.queryForObject("SELECT max(rejected_at) FROM rejected_frame", none, Timestamp.class);
        List<ReasonCount> byReason = jdbc.query("""
                SELECT split_part(rejection_reason, ':', 1) AS reason,
                       count(*) FILTER (WHERE rejected_at > now() - interval '24 hours') AS last_day, count(*) AS total
                FROM rejected_frame GROUP BY 1 ORDER BY 3 DESC LIMIT 20""", none,
                (rs, n) -> new ReasonCount(rs.getString("reason"), rs.getLong("last_day"), rs.getLong("total")));
        return new Summary(total == null ? 0 : total, lastDay == null ? 0 : lastDay, newest == null ? null : newest.toInstant(), byReason);
    }
}
