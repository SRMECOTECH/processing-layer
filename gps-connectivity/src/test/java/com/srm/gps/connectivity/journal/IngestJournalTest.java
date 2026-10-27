package com.srm.gps.connectivity.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ingest journal: nothing appended is forgotten until it is acknowledged, across restarts and torn writes. */
class IngestJournalTest {

    private static final String TOPIC = "gps.raw-device-frames";

    @TempDir
    Path directory;

    @Test
    void aSegmentIsDeletedOnlyOnceEveryRecordInItWasAcknowledged() throws IOException {
        IngestJournal journal = new IngestJournal(directory, 2, true);
        journal.append(TOPIC, "a", "1");
        journal.append(TOPIC, "a", "2");
        journal.append(TOPIC, "a", "3");          // rolls: the first segment holds 0 and 1
        assertEquals(2, segmentFiles());

        journal.acknowledge(0);
        assertEquals(2, segmentFiles(), "one record of the first segment is still unacknowledged");
        journal.acknowledge(1);

        assertEquals(1, segmentFiles());
        assertEquals(1, journal.unacknowledgedCount());
    }

    @Test
    void whatWasNotAcknowledgedIsStillThereAfterARestart() {
        IngestJournal journal = new IngestJournal(directory, 10, true);
        journal.append(TOPIC, "a", "1");
        journal.append(TOPIC, "a", "2");
        journal.append(TOPIC, "b", "3");
        journal.acknowledge(0);
        journal.acknowledge(1);
        journal.checkpoint();
        // the service dies here: no close()

        IngestJournal reopened = new IngestJournal(directory, 10, true);

        List<IngestJournal.Entry> pending = reopened.unacknowledged(100, sequence -> false);
        assertEquals(1, pending.size());
        assertEquals("3", pending.get(0).value());
        assertEquals("b", pending.get(0).key());
    }

    @Test
    void withoutACheckpointEverythingIsSentAgainRatherThanLost() {
        IngestJournal journal = new IngestJournal(directory, 10, true);
        journal.append(TOPIC, "a", "1");
        journal.append(TOPIC, "a", "2");
        journal.acknowledge(1);   // 0 is not acknowledged, so the checkpoint cannot move past it

        IngestJournal reopened = new IngestJournal(directory, 10, true);

        assertEquals(2, reopened.unacknowledgedCount(), "at least once: re-sending is safe, forgetting is not");
    }

    @Test
    void sequencesContinueAfterARestartAndNewRecordsQueueBehindOldOnes() {
        IngestJournal journal = new IngestJournal(directory, 10, true);
        journal.append(TOPIC, "a", "1");
        journal.append(TOPIC, "a", "2");

        IngestJournal reopened = new IngestJournal(directory, 10, true);
        IngestJournal.Entry next = reopened.append(TOPIC, "a", "3");

        assertEquals(2, next.sequence());
        assertEquals(List.of("1", "2", "3"), reopened.unacknowledged(100, s -> false).stream().map(IngestJournal.Entry::value).toList());
    }

    @Test
    void aHalfWrittenLastLineIsIgnoredAndEveryCompleteRecordKept() throws IOException {
        IngestJournal journal = new IngestJournal(directory, 10, true);
        journal.append(TOPIC, "a", "1");
        journal.append(TOPIC, "a", "2");
        Path segment;
        try (Stream<Path> files = Files.list(directory)) {
            segment = files.filter(p -> p.getFileName().toString().startsWith("segment-")).findFirst().orElseThrow();
        }
        Files.writeString(segment, "{\"sequence\":2,\"topic\":\"gps.raw", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IngestJournal reopened = new IngestJournal(directory, 10, true);

        assertEquals(2, reopened.unacknowledgedCount());
    }

    @Test
    void unacknowledgedIsOldestFirstAndLeavesOutWhatIsAlreadyOnItsWay() {
        IngestJournal journal = new IngestJournal(directory, 2, true);
        for (int i = 0; i < 5; i++) {
            journal.append(TOPIC, "a", String.valueOf(i));
        }
        journal.acknowledge(1);

        List<IngestJournal.Entry> pending = journal.unacknowledged(2, sequence -> sequence == 0);

        assertEquals(List.of(2L, 3L), pending.stream().map(IngestJournal.Entry::sequence).toList());
        assertTrue(journal.stats().bytesOnDisk() > 0);
    }

    private long segmentFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("segment-")).count();
        }
    }
}
