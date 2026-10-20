package com.srm.gps.application.streaming;

import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.speed.SpeedRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speed rules in Redis (gps:config:speed-rule:{scope}), so they can be changed while the stream runs.
 * The rule for a reading is looked up like Traccar's attribute lookup (device -> group -> server):
 * vehicle:{registration} -> tenant:{code} -> default.
 * Lookups are cached for a few seconds: one rule change reaches every consumer within that time.
 */
@Component
public class SpeedRuleStore {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedRuleStore.class);
    private static final long CACHE_MILLIS = 5_000;

    private final RedisStateStore redis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(SpeedRule rule, long loadedAtMillis) {
    }

    public SpeedRuleStore(RedisStateStore redis) {
        this.redis = redis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createDefaultRuleIfMissing() {
        try {
            if (redis.readHash(RedisKeyNames.speedRule(RedisKeyNames.DEFAULT_SPEED_RULE_SCOPE)).isEmpty()) {
                save(SpeedRule.defaults());
                LOG.info("Created the default speed rule in Redis: {}", SpeedRule.defaults());
            }
        } catch (RuntimeException redisDown) {
            LOG.warn("Could not create the default speed rule (Redis not answering): {}", redisDown.getMessage());
        }
    }

    /** The rule that applies to a vehicle of a company (either may be null). */
    public SpeedRule ruleFor(String vehicleRegistrationNo, String tenantCode) {
        if (vehicleRegistrationNo != null) {
            SpeedRule vehicleRule = cached(RedisKeyNames.vehicleScope(vehicleRegistrationNo));
            if (vehicleRule != null) {
                return vehicleRule;
            }
        }
        if (tenantCode != null) {
            SpeedRule tenantRule = cached(RedisKeyNames.tenantScope(tenantCode));
            if (tenantRule != null) {
                return tenantRule;
            }
        }
        SpeedRule defaultRule = cached(RedisKeyNames.DEFAULT_SPEED_RULE_SCOPE);
        return defaultRule != null ? defaultRule : SpeedRule.defaults();
    }

    public List<SpeedRule> all() {
        Set<String> scopes = redis.template().opsForSet().members(RedisKeyNames.SPEED_RULE_SCOPES);
        List<SpeedRule> rules = new ArrayList<>();
        if (scopes != null) {
            for (String scope : scopes) {
                Map<String, String> fields = redis.readHash(RedisKeyNames.speedRule(scope));
                if (!fields.isEmpty()) {
                    rules.add(StateCodecs.rule(scope, fields));
                }
            }
        }
        rules.sort((a, b) -> order(a.scope()) == order(b.scope()) ? a.scope().compareTo(b.scope()) : order(a.scope()) - order(b.scope()));
        return rules;
    }

    public SpeedRule save(SpeedRule rule) {
        redis.writeHash(RedisKeyNames.speedRule(rule.scope()), StateCodecs.rule(rule));
        redis.template().opsForSet().add(RedisKeyNames.SPEED_RULE_SCOPES, rule.scope());
        cache.clear();
        return rule;
    }

    public boolean delete(String scope) {
        if (RedisKeyNames.DEFAULT_SPEED_RULE_SCOPE.equals(scope)) {
            return false;
        }
        redis.template().delete(RedisKeyNames.speedRule(scope));
        redis.template().opsForSet().remove(RedisKeyNames.SPEED_RULE_SCOPES, scope);
        cache.clear();
        return true;
    }

    private SpeedRule cached(String scope) {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(scope);
        if (hit != null && now - hit.loadedAtMillis() < CACHE_MILLIS) {
            return hit.rule();
        }
        Map<String, String> fields = redis.readHash(RedisKeyNames.speedRule(scope));
        SpeedRule rule = fields.isEmpty() ? null : StateCodecs.rule(scope, fields);
        cache.put(scope, new Cached(rule, now));
        return rule;
    }

    private static int order(String scope) {
        if (RedisKeyNames.DEFAULT_SPEED_RULE_SCOPE.equals(scope)) {
            return 0;
        }
        return scope.startsWith("tenant:") ? 1 : 2;
    }
}
