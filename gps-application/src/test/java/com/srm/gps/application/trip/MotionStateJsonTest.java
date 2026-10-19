package com.srm.gps.application.trip;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.trip.DetectedStop;
import com.srm.gps.processing.trip.DetectedTrip;
import com.srm.gps.processing.trip.SegmentStatus;
import com.srm.gps.processing.trip.VehicleAssignment;
import com.srm.gps.processing.trip.WindowReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The jsonb columns of vehicle_motion_state must give back exactly what was saved. */
class MotionStateJsonTest {

    private static final Instant START = Instant.parse("2026-10-19T06:00:00Z");
    private static final VehicleAssignment TRUCK =
            new VehicleAssignment("6f1c1b8e-6a0e-4d55-9a54-0d2b9c3c1a11", "demo-transport", "XX01AB1234");

    @Test
    void theReadingWindowSurvivesTheRoundTrip() {
        List<WindowReading> window = List.of(
                new WindowReading(START, 18.52, 73.85, 0, 0),
                new WindowReading(START.plusSeconds(2), 18.5207, 73.8506, 36, 102.4));

        String json = GpsMessageJson.toJson(window);

        assertEquals(window, List.of(GpsMessageJson.fromJson(json, WindowReading[].class)));
    }

    @Test
    void openTripAndStopSurviveTheRoundTripWithoutExtraFields() {
        DetectedTrip trip = new DetectedTrip(TRUCK, START, 18.52, 73.85, START.plusSeconds(600), 18.57, 73.85,
                6000, 54, 61, SegmentStatus.OPEN);
        DetectedStop stop = new DetectedStop(null, START, START.plusSeconds(900), 18.52, 73.85, 31, SegmentStatus.OPEN);

        String tripJson = GpsMessageJson.toJson(trip);

        assertEquals(trip, GpsMessageJson.fromJson(tripJson, DetectedTrip.class));
        assertEquals(stop, GpsMessageJson.fromJson(GpsMessageJson.toJson(stop), DetectedStop.class));
        assertFalse(tripJson.contains("durationSeconds"), "derived values are not stored: " + tripJson);
    }
}
