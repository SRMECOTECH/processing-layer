package com.srm.gps.connectivity.journal;

import com.srm.gps.configuration.json.GpsMessageJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

/**
 * A write-ahead journal on local disk for records that must reach Kafka: a record is appended (and fsynced) BEFORE it
 * is sent, and stays until Kafka has acknowledged it. Kafka down for an hour, a full producer buffer, the service
 * killed mid-send, a power cut: whatever was appended is still in the journal and is sent again.
 *
 * Layout: segment-{firstSequence}.log files, one JSON line per record, rolled every recordsPerSegment records; a
 * segment is deleted once every record in it was acknowledged. acknowledged-through holds the highest sequence up to
 * which everything was acknowledged, so a restart re-sends only what was not (re-sending can repeat a record Kafka
 * already had; downstream de-duplicates by IMEI + recordedAt, so at-least-once here is safe).
 *
 * Thread-safe. Plain Java: the Kafka side lives in DurableKafkaSender.
 */
public final class IngestJournal {

    /** One journaled record. */
    public record Entry(long sequence, String topic, String key, String value, long journaledAtMillis) {
    }

    /** What the journal holds right now. */
    public record Stats(int segments, long unacknowledged, long bytesOnDisk, long appended, long acknowledged,
                        long acknowledgedThrough, Instant oldestUnacknowledgedSegmentAt) {
    }

    private static final String SEGMENT_PREFIX = "segment-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final String CHECKPOINT = "acknowledged-through";

    private final Path directory;
    private final int recordsPerSegment;
    private final boolean fsyncEveryRecord;

    private final TreeMap<Long, Segment> segments = new TreeMap<>();
    private Segment current;
    private FileChannel currentChannel;
    private long nextSequence;
    private long acknowledgedThrough;
    private long appended;
    private long acknowledged;

    private static final class Segment {
        final long firstSequence;
        final Path file;
        final BitSet acknowledged = new BitSet();
        final Instant createdAt;
        int count;
        int acknowledgedCount;
        boolean closed;

        Segment(long firstSequence, Path file, Instant createdAt) {
            this.firstSequence = firstSequence;
            this.file = file;
            this.createdAt = createdAt;
        }

        boolean contains(long sequence) {
            return sequence >= firstSequence && sequence < firstSequence + count;
        }
    }

    /**
     * Opens (and recovers) the journal in {@code directory}.
     *
     * @param fsyncEveryRecord true: every append is on disk before it returns (no window at all on a power cut);
     *                         false: the OS writes it out on its own schedule (survives a crash of the service only)
     */
    public IngestJournal(Path directory, int recordsPerSegment, boolean fsyncEveryRecord) {
        this.directory = directory;
        this.recordsPerSegment = recordsPerSegment;
        this.fsyncEveryRecord = fsyncEveryRecord;
        try {
            Files.createDirectories(directory);
            recover();
        } catch (IOException problem) {
            throw new UncheckedIOException("The ingest journal cannot use " + directory + ": " + problem.getMessage(), problem);
        }
    }

    /** Appends a record; when this returns, the record is on disk. */
    public synchronized Entry append(String topic, String key, String value) {
        Entry entry = new Entry(nextSequence, topic, key, value, System.currentTimeMillis());
        try {
            if (current == null || current.count >= recordsPerSegment) {
                roll();
            }
            ByteBuffer line = ByteBuffer.wrap((GpsMessageJson.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8));
            while (line.hasRemaining()) {
                currentChannel.write(line);
            }
            if (fsyncEveryRecord) {
                currentChannel.force(false);
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not journal a record for " + topic + ": " + problem.getMessage(), problem);
        }
        current.count++;
        nextSequence++;
        appended++;
        return entry;
    }

    /** Kafka has the record: it no longer needs to be kept. Deletes segments that are fully acknowledged. */
    public synchronized void acknowledge(long sequence) {
        Map.Entry<Long, Segment> holder = segments.floorEntry(sequence);
        if (holder == null || !holder.getValue().contains(sequence)) {
            return;   // already acknowledged and deleted
        }
        Segment segment = holder.getValue();
        int index = (int) (sequence - segment.firstSequence);
        if (segment.acknowledged.get(index)) {
            return;
        }
        segment.acknowledged.set(index);
        segment.acknowledgedCount++;
        acknowledged++;
        while (acknowledgedThrough + 1 < nextSequence && isAcknowledged(acknowledgedThrough + 1)) {
            acknowledgedThrough++;
        }
        if (segment.closed && segment.acknowledgedCount == segment.count) {
            delete(segment);
        }
    }

    /**
     * Records not acknowledged yet, oldest first: what has to be (re)sent.
     *
     * @param limit at most this many
     * @param skip  sequences to leave out (already on their way)
     */
    public synchronized List<Entry> unacknowledged(int limit, LongPredicate skip) {
        List<Entry> pending = new ArrayList<>();
        for (Segment segment : segments.values()) {
            if (pending.size() >= limit) {
                break;
            }
            if (segment.acknowledgedCount == segment.count) {
                continue;
            }
            for (Entry entry : read(segment.file)) {
                int index = (int) (entry.sequence() - segment.firstSequence);
                if (index < segment.count && !segment.acknowledged.get(index) && !skip.test(entry.sequence())) {
                    pending.add(entry);
                    if (pending.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return pending;
    }

    public synchronized long unacknowledgedCount() {
        long count = 0;
        for (Segment segment : segments.values()) {
            count += segment.count - segment.acknowledgedCount;
        }
        return count;
    }

    /** Writes acknowledged-through atomically, so a restart does not re-send what Kafka already has. */
    public synchronized void checkpoint() {
        Path temporary = directory.resolve(CHECKPOINT + ".tmp");
        try {
            Files.writeString(temporary, Long.toString(acknowledgedThrough), StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve(CHECKPOINT), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not write the journal checkpoint: " + problem.getMessage(), problem);
        }
    }

    public synchronized Stats stats() {
        long bytes = 0;
        Instant oldest = null;
        for (Segment segment : segments.values()) {
            try {
                bytes += Files.size(segment.file);
            } catch (IOException ignored) {
                // a segment deleted meanwhile has no size to count
            }
            if (oldest == null && segment.acknowledgedCount < segment.count) {
                oldest = segment.createdAt;
            }
        }
        return new Stats(segments.size(), unacknowledgedCount(), bytes, appended, acknowledged, acknowledgedThrough, oldest);
    }

    public Path directory() {
        return directory;
    }

    public boolean fsyncEveryRecord() {
        return fsyncEveryRecord;
    }

    public synchronized void close() {
        try {
            if (currentChannel != null) {
                currentChannel.force(false);
                currentChannel.close();
            }
            checkpoint();
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    // ---------------------------------------------------------------------------------------------------- internals

    private boolean isAcknowledged(long sequence) {
        Map.Entry<Long, Segment> holder = segments.floorEntry(sequence);
        if (holder == null || !holder.getValue().contains(sequence)) {
            return sequence < nextSequence;   // its segment was deleted: everything in it had been acknowledged
        }
        Segment segment = holder.getValue();
        return segment.acknowledged.get((int) (sequence - segment.firstSequence));
    }

    private void roll() throws IOException {
        if (currentChannel != null) {
            currentChannel.force(false);
            currentChannel.close();
            current.closed = true;
            if (current.acknowledgedCount == current.count) {
                delete(current);
            }
        }
        Path file = directory.resolve(String.format("%s%020d%s", SEGMENT_PREFIX, nextSequence, SEGMENT_SUFFIX));
        currentChannel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        current = new Segment(nextSequence, file, Instant.now());
        segments.put(current.firstSequence, current);
    }

    private void delete(Segment segment) {
        segments.remove(segment.firstSequence);
        try {
            Files.deleteIfExists(segment.file);
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not delete journal segment " + segment.file + ": " + problem.getMessage(), problem);
        }
    }

    /** Rebuilds the segment table from disk after a restart: everything after the checkpoint is unacknowledged. */
    private void recover() throws IOException {
        Path checkpoint = directory.resolve(CHECKPOINT);
        acknowledgedThrough = Files.exists(checkpoint) ? Long.parseLong(Files.readString(checkpoint).trim()) : -1;
        nextSequence = acknowledgedThrough + 1;
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(p -> p.getFileName().toString().startsWith(SEGMENT_PREFIX)).sorted().toList();
        }
        for (Path file : files) {
            List<Entry> entries = read(file);
            if (entries.isEmpty()) {
                Files.deleteIfExists(file);
                continue;
            }
            Segment segment = new Segment(entries.get(0).sequence(), file, Files.getLastModifiedTime(file).toInstant());
            segment.count = entries.size();
            segment.closed = true;
            for (Entry entry : entries) {
                if (entry.sequence() <= acknowledgedThrough) {
                    segment.acknowledged.set((int) (entry.sequence() - segment.firstSequence));
                    segment.acknowledgedCount++;
                }
                nextSequence = Math.max(nextSequence, entry.sequence() + 1);
            }
            if (segment.acknowledgedCount == segment.count) {
                Files.deleteIfExists(file);
            } else {
                segments.put(segment.firstSequence, segment);
            }
        }
    }

    /** Every complete line of a segment; a half-written last line (the service died mid-append) is ignored. */
    private static List<Entry> read(Path file) {
        List<Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(GpsMessageJson.fromJson(line, Entry.class));
                } catch (GpsMessageJson.InvalidGpsMessageException torn) {
                    break;   // only the last line can be torn: nothing after it was ever acknowledged to a caller
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read journal segment " + file + ": " + problem.getMessage(), problem);
        }
        return entries;
    }
}
