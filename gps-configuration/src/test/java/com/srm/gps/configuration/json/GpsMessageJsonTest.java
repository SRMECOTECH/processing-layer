package com.srm.gps.configuration.json;

import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsMessageJsonTest {

    @Test
    void rawFrameSurvivesARoundTripThroughJson() {
        RawGpsFrame frame = new RawGpsFrame("0861000000000001", Instant.parse("2026-07-13T00:25:17Z"), 22.797258, 86.209573, 12);

        String json = GpsMessageJson.toJson(frame);

        assertTrue(json.contains("\"recordedAt\":\"2026-07-13T00:25:17Z\""), json);
        assertEquals(frame, GpsMessageJson.fromJson(json, RawGpsFrame.class));
    }

    @Test
    void processedPositionSurvivesARoundTripThroughJson() {
        ProcessedGpsPosition position = new ProcessedGpsPosition("0861000000000001", Instant.parse("2026-07-13T00:25:17Z"),
                22.797258, 86.209573, 12, MovementStatus.MOVING, 35.5, 7, false, Instant.parse("2026-10-15T10:00:00Z"));

        assertEquals(position, GpsMessageJson.fromJson(GpsMessageJson.toJson(position), ProcessedGpsPosition.class));
    }

    @Test
    void brokenJsonIsReportedWithAClearException() {
        assertThrows(GpsMessageJson.InvalidGpsMessageException.class,
                () -> GpsMessageJson.fromJson("{not json", RawGpsFrame.class));
    }
}
