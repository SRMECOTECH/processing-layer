package com.srm.gps.connectivity.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonHttpTest {

    @Test
    void baseUrlDropsTrailingSlashes() {
        assertEquals("http://192.168.137.1:8002", JsonHttp.baseUrl(" http://192.168.137.1:8002/ "));
        assertEquals("https://valhalla.example:443", JsonHttp.baseUrl("https://valhalla.example:443//"));
    }

    @Test
    void baseUrlRefusesWhatIsNotAnHttpAddress() {
        assertThrows(IllegalArgumentException.class, () -> JsonHttp.baseUrl(" "));
        assertThrows(IllegalArgumentException.class, () -> JsonHttp.baseUrl("192.168.137.1:8002"));
        assertThrows(IllegalArgumentException.class, () -> JsonHttp.baseUrl("redis://192.168.137.1:6379"));
    }
}
