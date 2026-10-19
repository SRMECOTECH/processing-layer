package com.srm.gps.processing.trip;

import java.time.Duration;
import java.time.Instant;

/**
 * A stop: the vehicle stayed within minDistance of one point from start to end. Identified by (imei, startRecordedAt).
 * While OPEN, the end is the newest reading of the parked vehicle.
 */
public record DetectedStop(
        VehicleAssignment assignment,
        Instant startRecordedAt,
        Instant endRecordedAt,
        double latitude,
        double longitude,
        int readingCount,
        SegmentStatus status) {

    public long durationSeconds() {
        return Duration.between(startRecordedAt, endRecordedAt).toSeconds();
    }

    static DetectedStop openAt(VehicleAssignment assignment, WindowReading start) {
        return new DetectedStop(assignment, start.recordedAt(), start.recordedAt(),
                start.latitude(), start.longitude(), 1, SegmentStatus.OPEN);
    }

    DetectedStop extendedTo(WindowReading reading) {
        return new DetectedStop(assignment, startRecordedAt, reading.recordedAt(), latitude, longitude,
                readingCount + 1, status);
    }

    /**
     * Closes the stop where the next trip starts. The trip start may lie a few readings back (it is back-dated),
     * so those readings are taken out of this stop again.
     */
    DetectedStop closedAt(Instant end, int readingCountAtEnd) {
        return new DetectedStop(assignment, startRecordedAt, end, latitude, longitude,
                Math.max(1, readingCountAtEnd), SegmentStatus.CLOSED);
    }

    DetectedStop closed() {
        return closedAt(endRecordedAt, readingCount);
    }
}
