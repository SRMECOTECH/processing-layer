package com.srm.gps.streams.analytics;

import com.srm.gps.configuration.message.DistanceSource;
import com.srm.gps.configuration.message.ProcessedGpsPosition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shadow mode: how the listener's and Kafka Streams' result for ONE reading compare. The two outputs are joined on
 * IMEI (the key) and recordedAt (the event-time timestamp, with a join window of 0) - no re-keying needed.
 *
 *   MATCH         every compared field is equal
 *   MISMATCH      some field differs (listed in differences)
 *   WARMUP        one side measured from nothing (its first reading of the device): legs differ, nothing is wrong
 *   ONLY_LISTENER the listener accepted it, Kafka Streams did not (for example: a re-sent older reading it caught
 *                 as a duplicate, a stale reading, a record key that is not the IMEI)
 *   ONLY_STREAMS  the reverse
 *
 * processedAt and totalDistanceMeters are not compared: the moment differs by nature, and the running total
 * starts from a different base when the shadow starts after the listener.
 */
public record ParityVerdict(Outcome outcome, Instant recordedAt, List<String> differences) {

    public enum Outcome { MATCH, MISMATCH, WARMUP, ONLY_LISTENER, ONLY_STREAMS }

    public static ParityVerdict compare(ProcessedGpsPosition listener, ProcessedGpsPosition streams) {
        if (streams == null) {
            return new ParityVerdict(Outcome.ONLY_LISTENER, listener.recordedAt(), List.of());
        }
        if (listener == null) {
            return new ParityVerdict(Outcome.ONLY_STREAMS, streams.recordedAt(), List.of());
        }
        List<String> differences = new ArrayList<>();
        differ(differences, "latitude", listener.latitude(), streams.latitude());
        differ(differences, "longitude", listener.longitude(), streams.longitude());
        differ(differences, "speedKmph", listener.speedKmph(), streams.speedKmph());
        differ(differences, "movementStatus", listener.movementStatus(), streams.movementStatus());
        differ(differences, "distanceFromPreviousMeters", listener.distanceFromPreviousMeters(), streams.distanceFromPreviousMeters());
        differ(differences, "secondsSincePreviousReading", listener.secondsSincePreviousReading(), streams.secondsSincePreviousReading());
        differ(differences, "arrivedOutOfOrder", listener.arrivedOutOfOrder(), streams.arrivedOutOfOrder());
        differ(differences, "distanceSource", listener.distanceSourceOrGps(), streams.distanceSourceOrGps());
        differ(differences, "ignition", listener.ignition(), streams.ignition());
        differ(differences, "odometerMeters", listener.odometerMeters(), streams.odometerMeters());
        differ(differences, "impliedSpeedKmph", listener.impliedSpeedKmph(), streams.impliedSpeedKmph());

        if (differences.isEmpty()) {
            return new ParityVerdict(Outcome.MATCH, listener.recordedAt(), List.of());
        }
        boolean warmup = (streams.distanceSourceOrGps() == DistanceSource.FIRST_READING)
                != (listener.distanceSourceOrGps() == DistanceSource.FIRST_READING);
        return new ParityVerdict(warmup ? Outcome.WARMUP : Outcome.MISMATCH, listener.recordedAt(), List.copyOf(differences));
    }

    private static void differ(List<String> differences, String field, Object listener, Object streams) {
        if (!Objects.equals(listener, streams)) {
            differences.add(field + ": listener " + listener + ", streams " + streams);
        }
    }
}
