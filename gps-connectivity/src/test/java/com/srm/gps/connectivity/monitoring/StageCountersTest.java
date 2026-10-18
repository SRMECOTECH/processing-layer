package com.srm.gps.connectivity.monitoring;

import com.srm.gps.configuration.PipelineStage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StageCountersTest {

    private static final Instant NOON = Instant.parse("2026-10-18T12:00:00Z");

    private final StageCounters counters = new StageCounters();

    @Test
    void countsMessagesInTotalPerPartitionAndPerImei() {
        counters.recordMessage("111", 0, NOON);
        counters.recordMessage("111", 0, NOON);
        counters.recordMessage("222", 2, NOON);

        StageSnapshot snapshot = counters.snapshot(PipelineStage.POSITION_STORAGE, NOON.plusSeconds(1));

        assertEquals(3, snapshot.totalMessages());
        assertEquals(Map.of(0, 2L, 2, 1L), snapshot.messagesByPartition());
        assertEquals(Map.of("111", 2L, "222", 1L), snapshot.messagesByImei());
        assertEquals(NOON, snapshot.lastMessageAt());
    }

    @Test
    void messagesPerSecondIsTheAverageOfTheLastTenFullSeconds() {
        // 30 messages in each of 10 seconds
        for (int second = 0; second < 10; second++) {
            for (int message = 0; message < 30; message++) {
                counters.recordMessage("111", 0, NOON.plusSeconds(second));
            }
        }

        StageSnapshot snapshot = counters.snapshot(PipelineStage.POSITION_STORAGE, NOON.plusSeconds(10));

        assertEquals(30.0, snapshot.messagesPerSecond());
        assertEquals(60, snapshot.lastMinutePerSecond().size());
        assertEquals(30L, snapshot.lastMinutePerSecond().get(59));
    }

    @Test
    void secondsOlderThanOneMinuteAreForgotten() {
        counters.recordMessage("111", 0, NOON);

        StageSnapshot snapshot = counters.snapshot(PipelineStage.POSITION_STORAGE, NOON.plusSeconds(120));

        assertEquals(0.0, snapshot.messagesPerSecond());
        assertEquals(0L, snapshot.lastMinutePerSecond().stream().mapToLong(Long::longValue).sum());
        assertEquals(1, snapshot.totalMessages());
    }

    @Test
    void aStageWithoutMessagesHasNoLastMessageTime() {
        assertNull(counters.snapshot(PipelineStage.LIVE_LOCATION, NOON).lastMessageAt());
    }
}
