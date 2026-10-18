package com.srm.gps.processing;

import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.processing.step.InMemoryPreviousReadingStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpsFrameProcessorTest {

    private static final String IMEI = "0861000000000001";
    private static final Instant ONE_HOUR_AGO = Instant.now().minusSeconds(3600);

    private final GpsFrameProcessor processor = new GpsFrameProcessor(new InMemoryPreviousReadingStore());
    private final CollectingResultHandler results = new CollectingResultHandler();

    @Test
    void theFirstReadingOfADeviceHasNoDistance() {
        ProcessedGpsPosition position = accepted(frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));

        assertEquals(0, position.distanceFromPreviousMeters());
        assertEquals(0, position.secondsSincePreviousReading());
        assertEquals(MovementStatus.STOPPED, position.movementStatus());
    }

    @Test
    void theSecondReadingIsComparedWithTheFirst() {
        accepted(frame(ONE_HOUR_AGO, 22.7972, 86.2095, 0));

        // about 111 metres further north, 30 seconds later
        ProcessedGpsPosition position = accepted(frame(ONE_HOUR_AGO.plusSeconds(30), 22.7982, 86.2095, 0));

        assertTrue(position.distanceFromPreviousMeters() > 100);
        assertEquals(30, position.secondsSincePreviousReading());
        assertEquals(MovementStatus.MOVING, position.movementStatus());
    }

    @Test
    void theSameReadingSentTwiceIsRejectedAsDuplicate() {
        String frame = frame(ONE_HOUR_AGO, 22.7972, 86.2095, 10);
        accepted(frame);

        assertTrue(rejected(frame).rejectionReason().startsWith("Duplicate reading"));
    }

    @Test
    void aLateReadingIsFlaggedAndDoesNotReplaceTheNewestOne() {
        accepted(frame(ONE_HOUR_AGO.plusSeconds(60), 22.7972, 86.2095, 0));

        ProcessedGpsPosition late = accepted(frame(ONE_HOUR_AGO, 22.7000, 86.2000, 0));
        assertTrue(late.arrivedOutOfOrder());

        // the next reading is still compared with the newest one (60 s), not with the late one
        ProcessedGpsPosition next = accepted(frame(ONE_HOUR_AGO.plusSeconds(90), 22.7972, 86.2095, 0));
        assertFalse(next.arrivedOutOfOrder());
        assertEquals(30, next.secondsSincePreviousReading());
    }

    @Test
    void aFrameWhoseResultCouldNotBeDeliveredIsProcessedNormallyWhenRetried() {
        String frame = frame(ONE_HOUR_AGO, 22.7972, 86.2095, 10);
        ProcessingResultHandler brokenDelivery = new CollectingResultHandler() {
            @Override
            public void onAccepted(ProcessedGpsPosition position) {
                throw new IllegalStateException("PostgreSQL is down");
            }
        };

        assertThrows(IllegalStateException.class, () -> processor.process(frame, brokenDelivery));

        // the retry must not be mistaken for a duplicate
        accepted(frame);
    }

    @Test
    void textThatIsNotJsonIsRejected() {
        RejectedGpsFrame rejection = rejected("not json");

        assertEquals("Frame is not valid JSON", rejection.rejectionReason());
        assertEquals("not json", rejection.originalPayload());
    }

    @Test
    void anInvalidFrameIsRejectedWithTheReason() {
        RejectedGpsFrame rejection = rejected(frame(ONE_HOUR_AGO, 0.0, 0.0, 10));

        assertEquals("Position is 0,0 - the device has no GPS fix", rejection.rejectionReason());
    }

    private ProcessedGpsPosition accepted(String rawFrameJson) {
        results.clear();
        processor.process(rawFrameJson, results);
        assertEquals(1, results.accepted.size(), "expected the frame to be accepted, but: " + results.rejected);
        return results.accepted.getFirst();
    }

    private RejectedGpsFrame rejected(String rawFrameJson) {
        results.clear();
        processor.process(rawFrameJson, results);
        assertEquals(1, results.rejected.size(), "expected the frame to be rejected");
        return results.rejected.getFirst();
    }

    private static String frame(Instant recordedAt, double latitude, double longitude, int speedKmph) {
        return GpsMessageJson.toJson(new RawGpsFrame(IMEI, recordedAt, latitude, longitude, speedKmph));
    }

    private static class CollectingResultHandler implements ProcessingResultHandler {

        final List<ProcessedGpsPosition> accepted = new ArrayList<>();
        final List<RejectedGpsFrame> rejected = new ArrayList<>();

        @Override
        public void onAccepted(ProcessedGpsPosition position) {
            accepted.add(position);
        }

        @Override
        public void onRejected(RejectedGpsFrame rejection) {
            rejected.add(rejection);
        }

        void clear() {
            accepted.clear();
            rejected.clear();
        }
    }
}
