package com.srm.gps.processing.speed;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedPolicyTest {

    private static final SpeedRule RULE = SpeedRule.defaults();   // 60 km/h

    @Test
    void aStricterGeofenceLowersTheLimitAndSaysWhy() {
        SpeedRule inPlant = SpeedPolicy.defaults().effective(RULE, 20, "JAMSHEDPUR", LocalTime.NOON);
        assertEquals(20, inPlant.limitKmph());
        assertEquals("default+zone:JAMSHEDPUR", inPlant.scope());
        assertSame(RULE, SpeedPolicy.defaults().effective(RULE, 80, "HIGHWAY", LocalTime.NOON), "a looser zone changes nothing");
        assertSame(RULE, SpeedPolicy.defaults().effective(RULE, null, null, LocalTime.NOON));
    }

    @Test
    void nightIsStricterOnlyWhenSwitchedOnAndOnlyAtNight() {
        SpeedPolicy policy = new SpeedPolicy(10, 25, true, 10, 22, 5);
        assertEquals(50, policy.effective(RULE, null, null, LocalTime.of(23, 30)).limitKmph());
        assertEquals(50, policy.effective(RULE, null, null, LocalTime.of(4, 59)).limitKmph());
        assertEquals(60, policy.effective(RULE, null, null, LocalTime.of(5, 0)).limitKmph());
        assertEquals(10, policy.effective(RULE, 20, "PLANT", LocalTime.of(1, 0)).limitKmph(), "zone and night together");
        assertTrue(policy.effective(RULE, 20, "PLANT", LocalTime.of(1, 0)).scope().endsWith("+zone:PLANT+night"));
    }

    @Test
    void bandsFollowTheExcessAndASuspectSensorIsNotADriverMatter() {
        SpeedPolicy policy = SpeedPolicy.defaults();
        assertEquals(SpeedPolicy.Band.WARNING, policy.band(4, "GPS_VERIFIED"));
        assertEquals(SpeedPolicy.Band.BREACH, policy.band(10, "GPS_VERIFIED"));
        assertEquals(SpeedPolicy.Band.SEVERE, policy.band(31, "UNVERIFIED"));
        assertEquals(SpeedPolicy.Band.SUSPECT, policy.band(40, "SENSOR_SUSPECT"));
        assertEquals(SpeedPolicy.Band.SEVERE, new SpeedPolicy(10, 25, false, 0, 22, 5).band(40, "SENSOR_SUSPECT"));
    }
}
