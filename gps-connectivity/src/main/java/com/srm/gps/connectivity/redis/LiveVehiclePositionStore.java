package com.srm.gps.connectivity.redis;

import com.srm.gps.configuration.RedisKeyNames;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the latest position of every device in Redis:
 *
 *   gps:live:vehicle:{imei}  a hash with readable fields (look at it in RedisInsight)
 *   gps:live:positions       a GEO set: one point per device, so Redis can answer "which trucks are within 2 km of X?"
 *                            - the starting point for geofencing.
 */
@Component
public class LiveVehiclePositionStore {

    private final StringRedisTemplate redis;

    public LiveVehiclePositionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(LiveVehiclePosition position) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("imei", position.imei());
        fields.put("vehicleRegistrationNo", orEmpty(position.vehicleRegistrationNo()));
        fields.put("tenantCode", orEmpty(position.tenantCode()));
        fields.put("latitude", String.valueOf(position.latitude()));
        fields.put("longitude", String.valueOf(position.longitude()));
        fields.put("speedKmph", String.valueOf(position.speedKmph()));
        fields.put("movementStatus", position.movementStatus());
        fields.put("recordedAt", position.recordedAt().toString());
        fields.put("updatedAt", position.updatedAt().toString());

        redis.opsForHash().putAll(RedisKeyNames.liveVehicle(position.imei()), fields);
        redis.opsForGeo().add(RedisKeyNames.LIVE_POSITIONS_GEO, new Point(position.longitude(), position.latitude()), position.imei());
    }

    /** The newest reading Redis knows for the device, or empty. */
    public Optional<Instant> findRecordedAt(String imei) {
        Object recordedAt = redis.opsForHash().get(RedisKeyNames.liveVehicle(imei), "recordedAt");
        return recordedAt == null ? Optional.empty() : Optional.of(Instant.parse(recordedAt.toString()));
    }

    public Map<Object, Object> findFields(String imei) {
        return redis.opsForHash().entries(RedisKeyNames.liveVehicle(imei));
    }

    /** Number of devices that have a live position. */
    public long countVehicles() {
        Long count = redis.opsForZSet().zCard(RedisKeyNames.LIVE_POSITIONS_GEO);
        return count == null ? 0 : count;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
