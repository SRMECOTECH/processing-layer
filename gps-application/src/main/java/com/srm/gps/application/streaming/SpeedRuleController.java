package com.srm.gps.application.streaming;

import com.srm.gps.application.geofence.GeofenceDirectory;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.processing.geofence.Geofence;
import com.srm.gps.processing.speed.SpeedPolicy;
import com.srm.gps.processing.speed.SpeedRule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Speed rules (kept in Redis) and the vehicles over the limit right now.
 *
 *   GET    /api/speed/rules                 every rule: default, tenant:{code}, vehicle:{registration}
 *   PUT    /api/speed/rules/{scope}         create or change one (body: the rule's fields)
 *   DELETE /api/speed/rules/{scope}         remove a tenant or vehicle rule (the default stays)
 *   GET    /api/speed/active                vehicles in a speeding candidate or confirmed episode
 *   GET    /api/speed/policy                the alert policy: severity bands, night rule, sensor-suspect handling
 *   PUT    /api/speed/policy                change it (body: the policy's fields); live within 5 s
 *   GET    /api/speed/zones                 the geofences with a speed limit of their own
 */
@RestController
public class SpeedRuleController {

    private final SpeedRuleStore rules;
    private final RedisStateStore redis;
    private final SpeedPolicyStore policies;
    private final GeofenceDirectory geofences;

    public SpeedRuleController(SpeedRuleStore rules, RedisStateStore redis, SpeedPolicyStore policies, GeofenceDirectory geofences) {
        this.rules = rules;
        this.redis = redis;
        this.policies = policies;
        this.geofences = geofences;
    }

    @GetMapping("/api/speed/policy")
    public SpeedPolicy policy() {
        return policies.current();
    }

    @PutMapping("/api/speed/policy")
    public ResponseEntity<?> savePolicy(@RequestBody SpeedPolicy body) {
        return ResponseEntity.ok(policies.save(body));
    }

    @GetMapping("/api/speed/zones")
    public List<Map<String, Object>> zones() {
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Geofence f : geofences.index().fences()) {
            if (f.speedLimitKmph() != null) {
                zones.add(Map.of("id", f.id(), "code", f.code(), "name", f.name(), "role", f.role(),
                        "speedLimitKmph", f.speedLimitKmph(), "radiusMeters", Math.round(f.radiusMeters())));
            }
        }
        return zones;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }

    public record ActiveEpisode(String imei, String phase, String episodeStartedAt, double maxSpeedKmph, double averageSpeedKmph,
                                int overLimitReadings, Double lastGpsSpeedKmph, String lastVerdict, String ruleScope, double limitKmph) {
    }

    @GetMapping("/api/speed/rules")
    public List<SpeedRule> list() {
        return rules.all();
    }

    @PutMapping("/api/speed/rules/{scope}")
    public ResponseEntity<SpeedRule> save(@PathVariable String scope, @RequestBody SpeedRule body) {
        if (!scope.equals(RedisKeyNames.DEFAULT_SPEED_RULE_SCOPE) && !scope.startsWith("tenant:") && !scope.startsWith("vehicle:")) {
            return ResponseEntity.badRequest().build();
        }
        if (body.limitKmph() <= 0 || body.minConsecutivePings() < 1 || body.windowPings() < 2 || body.minDurationSeconds() < 0) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(rules.save(body.withScope(scope)));
    }

    @DeleteMapping("/api/speed/rules/{scope}")
    public ResponseEntity<Void> delete(@PathVariable String scope) {
        return rules.delete(scope) ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }

    @GetMapping("/api/speed/active")
    public List<ActiveEpisode> active() {
        List<ActiveEpisode> episodes = new ArrayList<>();
        for (String key : redis.scanKeys(RedisKeyNames.speedStatePattern())) {
            Map<String, String> state = redis.readHash(key);
            String phase = state.getOrDefault("phase", "NORMAL");
            if ("NORMAL".equals(phase)) {
                continue;
            }
            int readings = parseInt(state.get("episodeReadings"));
            double sum = parseDouble(state.get("speedSumKmph"));
            episodes.add(new ActiveEpisode(key.substring(key.lastIndexOf(':') + 1), phase, state.get("episodeStartedAt"),
                    parseDouble(state.get("maxSpeedKmph")), readings == 0 ? 0 : Math.round(sum / readings * 10) / 10.0,
                    parseInt(state.get("overLimitReadings")),
                    state.get("lastGpsSpeedKmph") == null || state.get("lastGpsSpeedKmph").isEmpty() ? null : parseDouble(state.get("lastGpsSpeedKmph")),
                    state.get("lastVerdict"), state.get("ruleScope"), parseDouble(state.get("ruleLimitKmph"))));
        }
        episodes.sort((a, b) -> Double.compare(b.maxSpeedKmph(), a.maxSpeedKmph()));
        return episodes;
    }

    private static int parseInt(String value) {
        return value == null || value.isEmpty() ? 0 : (int) Double.parseDouble(value);
    }

    private static double parseDouble(String value) {
        return value == null || value.isEmpty() ? 0 : Double.parseDouble(value);
    }
}
