package com.srm.gps.connectivity.monitoring;

import com.srm.gps.configuration.PipelineStage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counters of one stage. Thread-safe: several consumer threads record at the same time.
 *
 * Messages per second come from a ring of 60 one-second slots: slot = second % 60.
 * A slot is reset the first time a new second lands in it.
 */
class StageCounters {

    private static final int SECONDS_KEPT = 60;
    private static final int SECONDS_AVERAGED = 10;

    private final LongAdder totalMessages = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final Map<String, LongAdder> messagesByImei = new ConcurrentHashMap<>();
    private final Map<Integer, LongAdder> messagesByPartition = new ConcurrentHashMap<>();
    private final AtomicLongArray messagesInSlot = new AtomicLongArray(SECONDS_KEPT);
    private final AtomicLongArray secondOfSlot = new AtomicLongArray(SECONDS_KEPT);
    private volatile Instant lastMessageAt;

    void recordMessage(String imei, int partition, Instant now) {
        totalMessages.increment();
        if (imei != null) {
            messagesByImei.computeIfAbsent(imei, key -> new LongAdder()).increment();
        }
        messagesByPartition.computeIfAbsent(partition, key -> new LongAdder()).increment();
        countInSecond(now.getEpochSecond());
        lastMessageAt = now;
    }

    void recordFailure() {
        failures.increment();
    }

    StageSnapshot snapshot(PipelineStage stage, Instant now) {
        long currentSecond = now.getEpochSecond();

        // oldest first, the current (unfinished) second excluded
        List<Long> lastMinute = new ArrayList<>(SECONDS_KEPT);
        for (long second = currentSecond - SECONDS_KEPT; second < currentSecond; second++) {
            lastMinute.add(messagesIn(second));
        }
        long lastTenSeconds = lastMinute.subList(SECONDS_KEPT - SECONDS_AVERAGED, SECONDS_KEPT).stream()
                .mapToLong(Long::longValue).sum();

        Map<Integer, Long> byPartition = new TreeMap<>();
        messagesByPartition.forEach((partition, count) -> byPartition.put(partition, count.sum()));
        Map<String, Long> byImei = new TreeMap<>();
        messagesByImei.forEach((imei, count) -> byImei.put(imei, count.sum()));

        return new StageSnapshot(stage, totalMessages.sum(), failures.sum(),
                lastTenSeconds / (double) SECONDS_AVERAGED, lastMinute, byPartition, byImei, lastMessageAt);
    }

    private void countInSecond(long second) {
        int slot = (int) (second % SECONDS_KEPT);
        if (secondOfSlot.get(slot) != second) {
            synchronized (this) {
                if (secondOfSlot.get(slot) != second) {
                    messagesInSlot.set(slot, 0);
                    secondOfSlot.set(slot, second);
                }
            }
        }
        messagesInSlot.incrementAndGet(slot);
    }

    private long messagesIn(long second) {
        int slot = (int) (second % SECONDS_KEPT);
        return secondOfSlot.get(slot) == second ? messagesInSlot.get(slot) : 0;
    }
}
