package com.srm.gps.processing.trip;

import java.time.Duration;
import java.time.Instant;

/**
 * A trip: the vehicle drove from start to end. Identified by (imei, startRecordedAt).
 *
 * The distance is the sum of distanceFromPreviousMeters of the in-order readings after the start,
 * never "odometer at end - odometer at start", so a late reading can never make it negative.
 */
public record DetectedTrip(
        VehicleAssignment assignment,
        Instant startRecordedAt,
        double startLatitude,
        double startLongitude,
        Instant endRecordedAt,
        double endLatitude,
        double endLongitude,
        double distanceMeters,
        int maxSpeedKmph,
        int readingCount,
        SegmentStatus status) {

    public long durationSeconds() {
        return Duration.between(startRecordedAt, endRecordedAt).toSeconds();
    }

    /** Distance over duration, like Traccar's trip report; 0 for a trip without duration. */
    public double averageSpeedKmph() {
        long seconds = durationSeconds();
        return seconds == 0 ? 0 : distanceMeters / seconds * 3.6;
    }

    static DetectedTrip openAt(VehicleAssignment assignment, WindowReading start) {
        return new DetectedTrip(assignment, start.recordedAt(), start.latitude(), start.longitude(),
                start.recordedAt(), start.latitude(), start.longitude(), 0, start.speedKmph(), 1, SegmentStatus.OPEN);
    }

    /** The same trip, one reading longer: the reading's distance from the previous one is added. */
    DetectedTrip extendedTo(WindowReading reading) {
        return new DetectedTrip(assignment, startRecordedAt, startLatitude, startLongitude,
                reading.recordedAt(), reading.latitude(), reading.longitude(),
                distanceMeters + reading.distanceFromPreviousMeters(),
                Math.max(maxSpeedKmph, reading.speedKmph()), readingCount + 1, status);
    }

    DetectedTrip closed() {
        return new DetectedTrip(assignment, startRecordedAt, startLatitude, startLongitude,
                endRecordedAt, endLatitude, endLongitude, distanceMeters, maxSpeedKmph, readingCount,
                SegmentStatus.CLOSED);
    }
}
