package com.srm.gps.connectivity.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis switched off: the real stores and StringRedisTemplate run against it without a Redis and without failing. */
class SwitchedOffRedisTest {

    private final SwitchableRedisConnectionFactory factory = SwitchableRedisConnectionFactory.switchedOff(
            new RedisTarget("redis", 6379, 0), Duration.ofSeconds(1), Duration.ofSeconds(1));
    private final StringRedisTemplate template = template(factory);

    @AfterEach
    void close() {
        factory.destroy();
    }

    @Test
    void writesAreDroppedAndReadsFindNothing() {
        RedisStateStore state = new RedisStateStore(template);
        state.writeHash("gps:state:speed:1", Map.of("over", "true"));
        state.appendHistory("1", Map.of("speed", "80"), 1000);
        state.pushEvent("1", "{}", 50);

        assertTrue(state.readHash("gps:state:speed:1").isEmpty());
        assertTrue(state.readHistory("1", 10).isEmpty());
        assertEquals(0, state.historyLength("1"));
        assertTrue(state.recentEvents("1", 10).isEmpty());
        assertTrue(state.scanKeys("gps:*").isEmpty());
        assertEquals("none", state.type("gps:state:speed:1"));
        assertEquals(0, state.deleteTelemetry());
    }

    @Test
    void livePositionsAreEmpty() {
        LiveVehiclePositionStore live = new LiveVehiclePositionStore(template);
        live.save(new LiveVehiclePosition("1", "JH02", "t", 22.8, 86.2, 40, "MOVING", Instant.now(), Instant.now()));

        assertTrue(live.findRecordedAt("1").isEmpty());
        assertTrue(live.findFields("1").isEmpty());
        assertEquals(0, live.countVehicles());
        assertFalse(Boolean.TRUE.equals(template.hasKey("gps:live:vehicle:1")));
        assertTrue(template.opsForGeo().radius("gps:live:positions",
                new org.springframework.data.geo.Circle(new Point(86.2, 22.8), 2000)).getContent().isEmpty());
    }

    @Test
    void cannotBeSwitchedToAnotherRedis() {
        assertTrue(factory.isSwitchedOff());
        assertEquals("redis://redis:6379/0", factory.target().url());
        assertThrows(IllegalStateException.class, () -> factory.switchTo(new RedisTarget("other", 6379, 1), null));
    }

    private static StringRedisTemplate template(SwitchableRedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
