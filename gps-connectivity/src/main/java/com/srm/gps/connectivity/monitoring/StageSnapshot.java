package com.srm.gps.connectivity.monitoring;

import com.srm.gps.configuration.PipelineStage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A moment-in-time copy of one stage's counters.
 *
 * @param totalMessages       messages handled since the service started
 * @param failures            times handling a message threw (it is then retried)
 * @param messagesPerSecond   average over the last 10 seconds
 * @param lastMinutePerSecond messages in each of the last 60 seconds, oldest first
 * @param messagesByPartition partition -> messages (-1 = direct flow, no partition)
 * @param messagesByImei      IMEI -> messages
 * @param lastMessageAt       when the last message was handled, null if none yet
 */
public record StageSnapshot(
        PipelineStage stage,
        long totalMessages,
        long failures,
        double messagesPerSecond,
        List<Long> lastMinutePerSecond,
        Map<Integer, Long> messagesByPartition,
        Map<String, Long> messagesByImei,
        Instant lastMessageAt) {
}
