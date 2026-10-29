package com.srm.gps.streams.runtime;

import org.apache.kafka.streams.state.RocksDBConfigSetter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Cache;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.WriteBufferManager;

import java.util.Map;

/**
 * Puts ONE ceiling on the off-heap memory of every RocksDB store in this JVM (both apps, every task), following the
 * Kafka Streams memory-management guide: a shared block cache that also holds the index and filter blocks, and the
 * memtables counted against the same cache. Without it each store instance (stores x partitions) sizes its own
 * caches, so memory grows with every partition added - the opposite of what scaling out should do.
 *
 * Size: {@value #TOTAL_OFF_HEAP_BYTES} in the streams config (default 64 MiB), a quarter of it for memtables.
 */
public class BoundedMemoryRocksDbConfig implements RocksDBConfigSetter {

    public static final String TOTAL_OFF_HEAP_BYTES = "gps.rocksdb.total.off.heap.bytes";
    private static final long DEFAULT_TOTAL_OFF_HEAP_BYTES = 64L * 1024 * 1024;

    private static Cache cache;
    private static WriteBufferManager writeBufferManager;

    @Override
    public void setConfig(String storeName, Options options, Map<String, Object> configs) {
        Object configured = configs.get(TOTAL_OFF_HEAP_BYTES);
        initialise(configured == null ? DEFAULT_TOTAL_OFF_HEAP_BYTES : Long.parseLong(configured.toString()));

        BlockBasedTableConfig table = (BlockBasedTableConfig) options.tableFormatConfig();
        table.setBlockCache(cache);
        table.setCacheIndexAndFilterBlocks(true);
        table.setCacheIndexAndFilterBlocksWithHighPriority(true);
        table.setPinTopLevelIndexAndFilter(true);
        options.setWriteBufferManager(writeBufferManager);
        options.setTableFormatConfig(table);
    }

    @Override
    public void close(String storeName, Options options) {
        // shared by every store: never closed by one of them
    }

    private static synchronized void initialise(long totalBytes) {
        if (cache == null) {
            cache = new LRUCache(totalBytes, -1, false, 0.1);
            writeBufferManager = new WriteBufferManager(totalBytes / 4, cache);
        }
    }
}
