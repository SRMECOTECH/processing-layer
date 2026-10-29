package com.srm.gps.route.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoPointTest {

    @Test
    void readsLatitudeCommaLongitude() {
        assertEquals(new GeoPoint(22.8046, 86.2029), GeoPoint.parse(" 22.8046, 86.2029 "));
    }

    @Test
    void refusesTextThatIsNotAPoint() {
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.parse("22.8046"));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.parse("north,east"));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.parse(null));
    }

    @Test
    void refusesPointsOffTheGlobe() {
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.parse("95.0,86.2"));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.parse("22.8,186.2"));
    }
}
