package com.srm.gps.connectivity.redis;

import com.srm.gps.configuration.RedisKeyNames;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plain Redis access for the stream consumers' state:
 *   - one hash per vehicle and concern (gps:state:vehicle|speed|ignition|stoppage:{imei}),
 *   - a capped stream per vehicle for its recent history (gps:history:{imei}): ordered by time, trimmed on write
 *     (XADD + XTRIM MAXLEN ~), readable newest-first with XREVRANGE - the vehicle's historical context,
 *   - a capped list per vehicle for its latest events (gps:events:{imei}).
 * Values are strings; an empty string stands for "no value".
 */
@Component
public class RedisStateStore {

    private static final int DELETE_BATCH = 500;

    private final StringRedisTemplate redis;

    public RedisStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Map<String, String> readHash(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        Map<String, String> fields = new LinkedHashMap<>();
        raw.forEach((field, value) -> fields.put(field.toString(), value.toString()));
        return fields;
    }

    public void writeHash(String key, Map<String, String> fields) {
        redis.opsForHash().putAll(key, fields);
    }

    public void appendHistory(String imei, Map<String, String> fields, long maxLength) {
        String key = RedisKeyNames.history(imei);
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(key));
        redis.opsForStream().trim(key, maxLength, true);
    }

    /** Newest first; each entry carries its stream id under "id". */
    public List<Map<String, String>> readHistory(String imei, int count) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .reverseRange(RedisKeyNames.history(imei), Range.unbounded(), Limit.limit().count(count));
        List<Map<String, String>> entries = new ArrayList<>();
        if (records == null) {
            return entries;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id", record.getId().getValue());
            record.getValue().forEach((field, value) -> entry.put(field.toString(), value.toString()));
            entries.add(entry);
        }
        return entries;
    }

    public long historyLength(String imei) {
        Long size = redis.opsForStream().size(RedisKeyNames.history(imei));
        return size == null ? 0 : size;
    }

    public void pushEvent(String imei, String json, int keep) {
        String key = RedisKeyNames.events(imei);
        redis.opsForList().leftPush(key, json);
        redis.opsForList().trim(key, 0, keep - 1);
    }

    public List<String> recentEvents(String imei, int count) {
        List<String> events = redis.opsForList().range(RedisKeyNames.events(imei), 0, count - 1);
        return events == null ? List.of() : events;
    }

    /** All keys matching a pattern (SCAN, never KEYS), sorted. */
    public Set<String> scanKeys(String pattern) {
        Set<String> keys = new TreeSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    public String type(String key) {
        var type = redis.type(key);
        return type == null ? "none" : type.code();
    }

    /**
     * Deletes the platform's telemetry keys, never configuration (gps:config:*) nor other programs' keys in a shared
     * Redis. Returns how many were deleted.
     */
    public long deleteTelemetry() {
        List<String> batch = new ArrayList<>();
        long deleted = 0;
        for (String pattern : RedisKeyNames.TELEMETRY_PATTERNS) {
            for (String key : scanKeys(pattern)) {
                batch.add(key);
                if (batch.size() >= DELETE_BATCH) {
                    deleted += unlink(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            deleted += unlink(batch);
        }
        return deleted;
    }

    private long unlink(List<String> keys) {
        Long count = redis.unlink(keys);
        return count == null ? 0 : count;
    }

    public StringRedisTemplate template() {
        return redis;
    }
}
