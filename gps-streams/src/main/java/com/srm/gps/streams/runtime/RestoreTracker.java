package com.srm.gps.streams.runtime;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.processor.StateRestoreListener;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches state restoration: when a task starts on an instance whose local RocksDB copy is missing or behind
 * (first start, a moved task, a wiped state directory), Kafka Streams replays the store's changelog topic before
 * the task processes anything. This keeps the progress per store and partition for the dashboard.
 */
public final class RestoreTracker implements StateRestoreListener {

    /**
     * @param recordsToRestore changelog records between the local checkpoint and the end of the changelog
     * @param restored         replayed so far
     */
    public record Restoration(String store, String changelog, int partition, long recordsToRestore, long restored,
                              Instant startedAt, Instant endedAt, boolean done) {
    }

    private final Map<String, Restoration> restorations = new ConcurrentHashMap<>();

    @Override
    public void onRestoreStart(TopicPartition changelog, String store, long startingOffset, long endingOffset) {
        restorations.put(key(store, changelog), new Restoration(store, changelog.topic(), changelog.partition(),
                Math.max(0, endingOffset - startingOffset), 0, Instant.now(), null, false));
    }

    @Override
    public void onBatchRestored(TopicPartition changelog, String store, long batchEndOffset, long numRestored) {
        restorations.computeIfPresent(key(store, changelog), (key, r) -> new Restoration(r.store(), r.changelog(), r.partition(),
                r.recordsToRestore(), r.restored() + numRestored, r.startedAt(), null, false));
    }

    @Override
    public void onRestoreEnd(TopicPartition changelog, String store, long totalRestored) {
        restorations.compute(key(store, changelog), (key, r) -> r == null
                ? new Restoration(store, changelog.topic(), changelog.partition(), totalRestored, totalRestored, Instant.now(), Instant.now(), true)
                : new Restoration(r.store(), r.changelog(), r.partition(), r.recordsToRestore(), totalRestored, r.startedAt(), Instant.now(), true));
    }

    @Override
    public void onRestoreSuspended(TopicPartition changelog, String store, long totalRestored) {
        // the task moved away mid-restore: it is no longer this instance's job
        restorations.remove(key(store, changelog));
    }

    /** Newest first. */
    public List<Restoration> restorations() {
        return restorations.values().stream().sorted(Comparator.comparing(Restoration::startedAt).reversed()).toList();
    }

    public void clear() {
        restorations.clear();
    }

    private static String key(String store, TopicPartition changelog) {
        return store + "-" + changelog.partition();
    }
}
