package com.srm.gps.connectivity.journal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * gps.journal.* in application.yml: the ingest journal in front of Kafka.
 *
 * @param directory         where it lives; keep it on a disk that survives the service (Docker: a volume)
 * @param recordsPerSegment records per segment file before a new one is started (default 1000)
 * @param fsyncEveryRecord  default true: an append is on disk before it returns, so even a power cut loses nothing
 * @param drainBatchSize    how many journaled records are re-sent per round while catching up (default 5000)
 */
@ConfigurationProperties("gps.journal")
public record JournalProperties(String directory, Integer recordsPerSegment, Boolean fsyncEveryRecord, Integer drainBatchSize) {

    public Path directoryOrDefault() {
        return Path.of(directory != null && !directory.isBlank() ? directory : "./data/ingest-journal");
    }

    public int recordsPerSegmentOrDefault() {
        return recordsPerSegment != null ? recordsPerSegment : 1000;
    }

    public boolean fsyncEveryRecordOrDefault() {
        return fsyncEveryRecord == null || fsyncEveryRecord;
    }

    public int drainBatchSizeOrDefault() {
        return drainBatchSize != null ? drainBatchSize : 5000;
    }
}
