package com.srm.gps.connectivity.redis;

import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.connectivity.monitoring.StageSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copies the engine load counters to Redis, so they can be browsed in RedisInsight:
 *
 *   gps:engine:stage:{stage}          total, messagesPerSecond, failures, lastMessageAt, label, topic, consumerGroup
 *   gps:engine:stage:{stage}:by-imei  IMEI -> messages handled by that stage
 */
@Component
public class EngineMetricsRedisMirror {

    private final StringRedisTemplate redis;

    public EngineMetricsRedisMirror(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void write(StageSnapshot snapshot) {
        PipelineStage stage = snapshot.stage();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("label", stage.label());
        fields.put("kind", stage.kind().name());
        fields.put("topic", stage.topic() == null ? "" : stage.topic());
        fields.put("consumerGroup", stage.consumerGroup() == null ? "" : stage.consumerGroup());
        fields.put("totalMessages", String.valueOf(snapshot.totalMessages()));
        fields.put("messagesPerSecond", String.valueOf(snapshot.messagesPerSecond()));
        fields.put("failures", String.valueOf(snapshot.failures()));
        fields.put("lastMessageAt", snapshot.lastMessageAt() == null ? "" : snapshot.lastMessageAt().toString());
        redis.opsForHash().putAll(RedisKeyNames.engineStage(stage), fields);

        if (!snapshot.messagesByImei().isEmpty()) {
            Map<String, String> byImei = new HashMap<>();
            snapshot.messagesByImei().forEach((imei, count) -> byImei.put(imei, String.valueOf(count)));
            redis.opsForHash().putAll(RedisKeyNames.engineStageByImei(stage), byImei);
        }
    }
}
