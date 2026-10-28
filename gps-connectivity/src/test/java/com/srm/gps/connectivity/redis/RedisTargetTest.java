package com.srm.gps.connectivity.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisTargetTest {

    @Test
    void readsHostPortAndDatabase() {
        assertEquals(new RedisTarget("192.168.137.1", 6379, 1), RedisTarget.parse("redis://192.168.137.1:6379/1"));
        assertEquals(new RedisTarget("redis", 6379, 0), RedisTarget.parse(" redis://redis:6379 "));
    }

    @Test
    void portAndDatabaseHaveDefaults() {
        assertEquals(new RedisTarget("localhost", 6379, 0), RedisTarget.parse("redis://localhost"));
        assertEquals(new RedisTarget("localhost", 6379, 0), RedisTarget.parse("redis://localhost/"));
        assertEquals(new RedisTarget("localhost", 6380, 2), RedisTarget.parse("redis://localhost:6380/2/"));
    }

    @Test
    void writesTheSameAddressBack() {
        assertEquals("redis://192.168.137.1:6379/1", RedisTarget.parse("redis://192.168.137.1:6379/1").url());
        assertEquals("redis://localhost:6379/0", RedisTarget.parse("redis://localhost").url());
    }

    @Test
    void refusesWhatIsNotARedisAddress() {
        assertThrows(IllegalArgumentException.class, () -> RedisTarget.parse(""));
        assertThrows(IllegalArgumentException.class, () -> RedisTarget.parse("http://192.168.137.1:6379"));
        assertThrows(IllegalArgumentException.class, () -> RedisTarget.parse("192.168.137.1:6379"));
        assertThrows(IllegalArgumentException.class, () -> RedisTarget.parse("redis://192.168.137.1:6379/one"));
        assertThrows(IllegalArgumentException.class, () -> RedisTarget.parse("redis://192.168.137.1:6379/16"));
        assertThrows(IllegalArgumentException.class, () -> RedisTarget.parse("redis://user:secret@192.168.137.1:6379/1"));
    }
}
