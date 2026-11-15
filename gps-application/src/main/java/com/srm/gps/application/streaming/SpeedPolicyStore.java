package com.srm.gps.application.streaming;

import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.speed.SpeedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The over-speed alert policy in Redis (gps:config:speed-policy, configuration: it survives the demo reset), edited on
 * Alerts -> Over-speed -> Alert policy or with PUT /api/speed/policy. Cached for 5 s like the speed rules, so a change
 * reaches every consumer within that time. Redis down: the last policy read, or the defaults.
 */
@Component
public class SpeedPolicyStore {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedPolicyStore.class);
    private static final long CACHE_MILLIS = 5_000;

    private final RedisStateStore redis;
    private volatile SpeedPolicy cached = SpeedPolicy.defaults();
    private volatile long loadedAtMillis;

    public SpeedPolicyStore(RedisStateStore redis) {
        this.redis = redis;
    }

    public SpeedPolicy current() {
        long now = System.currentTimeMillis();
        if (now - loadedAtMillis < CACHE_MILLIS) {
            return cached;
        }
        try {
            Map<String, String> fields = redis.readHash(RedisKeyNames.SPEED_POLICY);
            cached = fields.isEmpty() ? SpeedPolicy.defaults() : decode(fields);
        } catch (RuntimeException unreadable) {
            LOG.debug("Speed policy not read, keeping the last one: {}", unreadable.getMessage());
        }
        loadedAtMillis = now;
        return cached;
    }

    public SpeedPolicy save(SpeedPolicy policy) {
        redis.writeHash(RedisKeyNames.SPEED_POLICY, encode(policy));
        cached = policy;
        loadedAtMillis = System.currentTimeMillis();
        return policy;
    }

    static Map<String, String> encode(SpeedPolicy p) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("breachOverKmph", String.valueOf(p.breachOverKmph()));
        f.put("severeOverKmph", String.valueOf(p.severeOverKmph()));
        f.put("suppressSensorSuspect", String.valueOf(p.suppressSensorSuspect()));
        f.put("nightStricterKmph", String.valueOf(p.nightStricterKmph()));
        f.put("nightStartHour", String.valueOf(p.nightStartHour()));
        f.put("nightEndHour", String.valueOf(p.nightEndHour()));
        return f;
    }

    static SpeedPolicy decode(Map<String, String> f) {
        SpeedPolicy d = SpeedPolicy.defaults();
        return new SpeedPolicy(
                Double.parseDouble(f.getOrDefault("breachOverKmph", String.valueOf(d.breachOverKmph()))),
                Double.parseDouble(f.getOrDefault("severeOverKmph", String.valueOf(d.severeOverKmph()))),
                Boolean.parseBoolean(f.getOrDefault("suppressSensorSuspect", String.valueOf(d.suppressSensorSuspect()))),
                Double.parseDouble(f.getOrDefault("nightStricterKmph", String.valueOf(d.nightStricterKmph()))),
                Integer.parseInt(f.getOrDefault("nightStartHour", String.valueOf(d.nightStartHour()))),
                Integer.parseInt(f.getOrDefault("nightEndHour", String.valueOf(d.nightEndHour()))));
    }
}
