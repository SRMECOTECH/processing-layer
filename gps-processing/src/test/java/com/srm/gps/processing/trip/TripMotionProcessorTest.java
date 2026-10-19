package com.srm.gps.processing.trip;

import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.processing.GpsFrameProcessor;
import com.srm.gps.processing.ProcessingResultHandler;
import com.srm.gps.processing.rule.GeoDistanceCalculator;
import com.srm.gps.processing.step.InMemoryPreviousReadingStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripMotionProcessorTest {

    private static final String IMEI = "0861000000000001";
    private static final Instant T0 = Instant.parse("2026-10-01T06:00:00Z");
    private static final double BASE_LATITUDE = 18.52;
    private static final double BASE_LONGITUDE = 73.85;
    private static final VehicleAssignment TRUCK_A = new VehicleAssignment("a-1", "demo-transport", "XX01AB1234");
    private static final VehicleAssignment TRUCK_B = new VehicleAssignment("b-1", "demo-transport", "XX01CD5678");

    // ---------------------------------------------------------------- replay of the Traccar runtime drive

    /**
     * The scripted OsmAnd drive Traccar was fed in the R&D study, sent through OUR processing first.
     * Traccar (minDuration 20 s) reported deviceMoving on position 10 and deviceStopped on position 40;
     * a late reading (line 46) and an exact duplicate (line 47) followed.
     */
    @Test
    void replayOfTheTraccarDriveMovesAtReading10AndStopsAtReading40() throws IOException {
        TripDetectionSettings traccarDriveSettings = new TripDetectionSettings(200, 20, 3600);
        List<RawGpsFrame> frames = readTraccarDrive();
        Map<Integer, ProcessedGpsPosition> processedByLine = processLikeThePipeline(frames);

        Map<String, Recorder> recorderByImei = new LinkedHashMap<>();
        Map<Integer, MotionUpdate> updateByLine = new LinkedHashMap<>();
        processedByLine.forEach((line, position) -> {
            Recorder recorder = recorderByImei.computeIfAbsent(position.imei(),
                    imei -> new Recorder(imei, traccarDriveSettings));
            updateByLine.put(line, recorder.apply(TripReading.from(position, null)));
        });

        Recorder truck1 = recorderByImei.get("358000000000001");
        assertEquals(List.of(
                        MotionEvent.at(MotionEvent.Type.MOVING, windowReadingOf(frames.get(9))),
                        MotionEvent.at(MotionEvent.Type.STOPPED, windowReadingOf(frames.get(39)))),
                truck1.events);

        assertEquals(1, truck1.trips.size());
        DetectedTrip trip = truck1.trips.values().iterator().next();
        assertEquals(SegmentStatus.CLOSED, trip.status());
        assertEquals(frames.get(9).recordedAt(), trip.startRecordedAt());
        assertEquals(frames.get(39).recordedAt(), trip.endRecordedAt());
        double sumOfProcessedDistances = 0;
        for (int line = 11; line <= 40; line++) {
            sumOfProcessedDistances += processedByLine.get(line).distanceFromPreviousMeters();
        }
        assertEquals(sumOfProcessedDistances, trip.distanceMeters(), 0.001);
        assertTrue(trip.distanceMeters() > 2000, "about 20 legs of ~102 m, was " + trip.distanceMeters());
        assertEquals(31, trip.readingCount());
        assertEquals(85, trip.maxSpeedKmph());

        DetectedStop stop = truck1.stops.values().iterator().next();
        assertEquals(SegmentStatus.OPEN, stop.status());
        assertEquals(frames.get(39).recordedAt(), stop.startRecordedAt());
        assertEquals(frames.get(44).recordedAt(), stop.endRecordedAt());

        // Line 46: 5 minutes late. Traccar turned it into a trip of -1020 m; here it is skipped.
        assertTrue(processedByLine.get(46).arrivedOutOfOrder());
        assertFalse(updateByLine.get(46).applied());
        // Line 47: same time as line 45 - our processing already rejects it as a duplicate.
        assertNull(processedByLine.get(47));

        Recorder truck2 = recorderByImei.get("358000000000002");
        assertTrue(truck2.events.isEmpty());
        assertTrue(truck2.trips.isEmpty());
    }

    // ---------------------------------------------------------------- trips and stops with the defaults

    @Test
    void aTripStartsWhereTheVehicleStartedRollingAndEndsAfterMinDuration() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        long departure = park(truck, 0, 600, 30, 0);              // parked 10 minutes
        long arrival = drive(truck, departure + 10, 600, 10, 10);  // 10 m/s for 10 minutes = 6 km
        long lastParked = park(truck, arrival + 30, 900, 30, 6000); // parked 15 minutes

        assertEquals(2, truck.events.size());
        assertEquals(MotionEvent.Type.MOVING, truck.events.get(0).type());
        assertEquals(at(departure), truck.events.get(0).recordedAt(), "back-dated to the last parked reading");
        assertEquals(MotionEvent.Type.STOPPED, truck.events.get(1).type());
        Instant stoppedAt = truck.events.get(1).recordedAt();
        assertTrue(!stoppedAt.isBefore(at(arrival + 180)) && !stoppedAt.isAfter(at(arrival + 210)),
                "stopped " + Duration.between(at(arrival), stoppedAt).toSeconds() + " s after arrival");

        DetectedTrip trip = onlyTrip(truck);
        assertEquals(SegmentStatus.CLOSED, trip.status());
        assertEquals(at(departure), trip.startRecordedAt());
        assertEquals(stoppedAt, trip.endRecordedAt());
        assertEquals(6000, trip.distanceMeters(), 1);
        assertEquals(36, trip.maxSpeedKmph());

        // the first stop is only known from the first reading (no STOPPED event before it), so the only stop is the last one
        assertEquals(1, truck.stops.size());
        DetectedStop stop = truck.stops.get(stoppedAt);
        assertEquals(SegmentStatus.OPEN, stop.status());
        assertEquals(at(lastParked), stop.endRecordedAt());
    }

    @Test
    void theStopBeforeATripEndsWhereTheTripStarts() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        long arrival = drive(truck, 0, 300, 10, 10);
        long departure = park(truck, arrival + 30, 900, 30, 3000);
        drive(truck, departure + 10, 300, 10, 10, 3000);

        assertEquals(List.of(MotionEvent.Type.MOVING, MotionEvent.Type.STOPPED, MotionEvent.Type.MOVING),
                truck.events.stream().map(MotionEvent::type).toList());
        DetectedStop stop = onlyStop(truck);
        assertEquals(SegmentStatus.CLOSED, stop.status());
        assertEquals(truck.events.get(1).recordedAt(), stop.startRecordedAt());
        assertEquals(at(departure), stop.endRecordedAt());
        assertTrue(stop.durationSeconds() >= 600);

        List<DetectedTrip> trips = List.copyOf(truck.trips.values());
        assertEquals(2, trips.size());
        assertEquals(at(departure), trips.get(1).startRecordedAt());
        assertEquals(SegmentStatus.OPEN, trips.get(1).status());
        assertEquals(3000, trips.get(1).distanceMeters(), 1);
    }

    @Test
    void tripDistanceIsTheSumOfTheLegsNotEndMinusStart() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        long t = park(truck, 0, 300, 30, 0);
        // a 1 km square that ends where it started
        t = driveTo(truck, t + 10, 0, 0, 1000, 0, 10);
        t = driveTo(truck, t + 10, 1000, 0, 1000, 1000, 10);
        t = driveTo(truck, t + 10, 1000, 1000, 0, 1000, 10);
        t = driveTo(truck, t + 10, 0, 1000, 0, 0, 10);
        park(truck, t + 30, 600, 30, 0, 0);

        DetectedTrip trip = onlyTrip(truck);
        assertEquals(SegmentStatus.CLOSED, trip.status());
        assertEquals(4000, trip.distanceMeters(), 5);
        assertTrue(distanceBetweenEnds(trip) < 1, "starts and ends at the same place");
    }

    // ---------------------------------------------------------------- late and repeated readings

    @Test
    void aLateOrRepeatedReadingIsSkippedAndChangesNothing() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        park(truck, 0, 300, 30, 0);
        long t = drive(truck, 310, 300, 10, 10);
        MotionState before = truck.state;
        DetectedTrip tripBefore = onlyTrip(truck);

        MotionUpdate late = truck.applyLate(at(t - 200), east(500));
        MotionUpdate repeated = truck.applyLate(at(t), east(3000));

        for (MotionUpdate update : List.of(late, repeated)) {
            assertFalse(update.applied());
            assertSame(before, update.state());
            assertTrue(update.events().isEmpty() && update.trips().isEmpty() && update.stops().isEmpty());
        }
        assertEquals(tripBefore, onlyTrip(truck));
        assertTrue(onlyTrip(truck).distanceMeters() > 0);
    }

    @Test
    void theSameReadingsAlwaysGiveTheSameTrips() {
        Recorder first = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        Recorder second = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        for (Recorder truck : List.of(first, second)) {
            long departure = park(truck, 0, 600, 30, 0);
            long arrival = drive(truck, departure + 10, 600, 10, 10);
            park(truck, arrival + 30, 900, 30, 6000);
        }
        assertEquals(first.trips, second.trips);
        assertEquals(first.stops, second.stops);
        assertEquals(first.state, second.state);
    }

    // ---------------------------------------------------------------- data gaps

    @Test
    void silenceLongerThanStopGapEndsTheTripAtTheLastReadingBeforeIt() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        park(truck, 0, 300, 30, 0);
        long lastBeforeGap = drive(truck, 310, 300, 10, 10);   // driving when the device goes silent
        long afterGap = lastBeforeGap + 7200;
        truck.apply(at(afterGap), east(3000 + 50), 0);         // 2 hours later, 50 m further: it did not drive
        park(truck, afterGap + 30, 600, 30, 3050);

        DetectedTrip trip = onlyTrip(truck);
        assertEquals(SegmentStatus.CLOSED, trip.status());
        assertEquals(at(lastBeforeGap), trip.endRecordedAt(), "the silence is not part of the trip");
        assertEquals(3000, trip.distanceMeters(), 1);

        DetectedStop stop = onlyStop(truck);
        assertEquals(at(lastBeforeGap), stop.startRecordedAt());
        assertEquals(SegmentStatus.OPEN, stop.status());
        assertFalse(truck.state.moving());
    }

    @Test
    void silenceDuringWhichTheVehicleClearlyDroveIsRecordedAsStopTripStop() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        park(truck, 0, 300, 30, 0);
        long lastBeforeGap = drive(truck, 310, 300, 10, 10);
        long arrival = park(truck, lastBeforeGap + 30, 600, 30, 3000);  // a stop is open now
        long afterGap = arrival + 7200;
        MotionUpdate update = truck.apply(at(afterGap), east(3000 + 50_000), 0); // 50 km in 2 hours

        assertEquals(List.of(MotionEvent.Type.MOVING, MotionEvent.Type.STOPPED),
                update.events().stream().map(MotionEvent::type).toList());
        List<DetectedTrip> trips = List.copyOf(truck.trips.values());
        assertEquals(2, trips.size());
        DetectedTrip gapTrip = trips.get(1);
        assertEquals(SegmentStatus.CLOSED, gapTrip.status());
        assertEquals(at(arrival), gapTrip.startRecordedAt());
        assertEquals(at(afterGap), gapTrip.endRecordedAt());
        assertEquals(50_000, gapTrip.distanceMeters(), 50);

        List<DetectedStop> stops = List.copyOf(truck.stops.values());
        assertEquals(2, stops.size());
        assertEquals(SegmentStatus.CLOSED, stops.get(0).status());
        assertEquals(at(arrival), stops.get(0).endRecordedAt());
        assertEquals(at(afterGap), stops.get(1).startRecordedAt());
        assertEquals(SegmentStatus.OPEN, stops.get(1).status());
    }

    @Test
    void silenceWhileDrivingAfterWhichTheVehicleIsFarAwayClosesTheTripAndRecordsTheGapTrip() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        park(truck, 0, 300, 30, 0);
        long lastBeforeGap = drive(truck, 310, 300, 10, 10);
        truck.apply(at(lastBeforeGap + 5400), east(3000 + 40_000), 0);

        List<DetectedTrip> trips = List.copyOf(truck.trips.values());
        assertEquals(2, trips.size());
        assertEquals(at(lastBeforeGap), trips.get(0).endRecordedAt());
        assertEquals(SegmentStatus.CLOSED, trips.get(0).status());
        assertEquals(at(lastBeforeGap), trips.get(1).startRecordedAt());
        assertEquals(SegmentStatus.CLOSED, trips.get(1).status());
        assertEquals(SegmentStatus.OPEN, onlyStop(truck).status());
    }

    // ---------------------------------------------------------------- GPS jitter

    @Test
    void gpsJitterOfAParkedTruckNeverStartsATrip() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        Random jitter = new Random(42);
        for (long second = 0; second <= 3600; second += 10) {
            double north = jitter.nextGaussian() * 10;
            double east = jitter.nextGaussian() * 10;
            if (second % 600 == 300) {
                east += 120;                      // an occasional bad fix, 120 m off
            }
            truck.apply(at(second), point(east, north), jitter.nextInt(4));
        }
        assertTrue(truck.events.isEmpty());
        assertTrue(truck.trips.isEmpty());
    }

    @Test
    void gpsJitterAfterArrivalDoesNotKeepTheTripOpen() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        Random jitter = new Random(7);
        park(truck, 0, 300, 30, 0);
        long arrival = drive(truck, 310, 300, 10, 10);
        for (long second = arrival + 10; second <= arrival + 900; second += 10) {
            truck.apply(at(second), point(3000 + jitter.nextGaussian() * 15, jitter.nextGaussian() * 15), 0);
        }

        assertEquals(SegmentStatus.CLOSED, onlyTrip(truck).status());
        assertFalse(truck.state.moving());
        assertEquals(SegmentStatus.OPEN, onlyStop(truck).status());
    }

    // ---------------------------------------------------------------- master data

    @Test
    void movingTheDeviceToAnotherVehicleClosesTheTrip() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        truck.assignment = TRUCK_A;
        park(truck, 0, 300, 30, 0);
        long lastOnTruckA = drive(truck, 310, 300, 10, 10);

        truck.assignment = TRUCK_B;
        long t = drive(truck, lastOnTruckA + 10, 300, 10, 10, 3000);

        List<DetectedTrip> trips = List.copyOf(truck.trips.values());
        assertEquals(2, trips.size());
        assertEquals(TRUCK_A, trips.get(0).assignment());
        assertEquals(SegmentStatus.CLOSED, trips.get(0).status());
        assertEquals(at(lastOnTruckA), trips.get(0).endRecordedAt());
        assertEquals(TRUCK_B, trips.get(1).assignment());
        assertEquals(SegmentStatus.OPEN, trips.get(1).status());
        assertTrue(trips.get(1).startRecordedAt().isAfter(at(lastOnTruckA)));
        assertEquals(at(t), trips.get(1).endRecordedAt());
        assertEquals(TRUCK_B, truck.state.assignment());
    }

    // ---------------------------------------------------------------- state size

    @Test
    void theWindowCoversMinDurationAndNoMore() {
        Recorder truck = new Recorder(IMEI, TripDetectionSettings.DEFAULTS);
        long last = drive(truck, 0, 1200, 2, 10);
        List<WindowReading> window = truck.state.window();
        long spanSeconds = Duration.between(window.getFirst().recordedAt(), window.getLast().recordedAt()).toSeconds();
        assertTrue(spanSeconds >= 180 && spanSeconds < 184, "span " + spanSeconds);
        assertEquals(at(last), window.getLast().recordedAt());
    }

    // ================================================================ helpers

    /** Feeds readings one by one, like the consumer, and keeps the newest version of every trip and stop (like the upsert). */
    private static final class Recorder {
        private final TripDetectionSettings settings;
        private MotionState state;
        private VehicleAssignment assignment;
        private Instant previousAt;
        private double previousLatitude;
        private double previousLongitude;
        private final List<MotionEvent> events = new ArrayList<>();
        private final Map<Instant, DetectedTrip> trips = new LinkedHashMap<>();
        private final Map<Instant, DetectedStop> stops = new LinkedHashMap<>();

        Recorder(String imei, TripDetectionSettings settings) {
            this.settings = settings;
            this.state = MotionState.unknownDevice(imei);
        }

        /** An in-order reading, with its distance from the previous one calculated like the processing does. */
        MotionUpdate apply(Instant recordedAt, double[] latLon, int speedKmph) {
            double distance = previousAt == null ? 0 : Math.round(GeoDistanceCalculator.distanceInMeters(
                    previousLatitude, previousLongitude, latLon[0], latLon[1]) * 10) / 10.0;
            previousAt = recordedAt;
            previousLatitude = latLon[0];
            previousLongitude = latLon[1];
            return apply(new TripReading(recordedAt, latLon[0], latLon[1], speedKmph, distance, assignment));
        }

        /** A late reading: the processing gives it no distance. */
        MotionUpdate applyLate(Instant recordedAt, double[] latLon) {
            return apply(new TripReading(recordedAt, latLon[0], latLon[1], 0, 0, assignment));
        }

        MotionUpdate apply(TripReading reading) {
            MotionUpdate update = TripMotionProcessor.updateState(state, reading, settings);
            state = update.state();
            events.addAll(update.events());
            update.trips().forEach(trip -> trips.put(trip.startRecordedAt(), trip));
            update.stops().forEach(stop -> stops.put(stop.startRecordedAt(), stop));
            return update;
        }
    }

    /** Parks at {@code eastMeters} (north 0) with a reading every {@code everySeconds}; returns the last reading's time. */
    private static long park(Recorder truck, long fromSecond, long forSeconds, long everySeconds, double eastMeters) {
        return park(truck, fromSecond, forSeconds, everySeconds, eastMeters, 0);
    }

    private static long park(Recorder truck, long fromSecond, long forSeconds, long everySeconds,
                             double eastMeters, double northMeters) {
        long second = fromSecond;
        for (; second <= fromSecond + forSeconds; second += everySeconds) {
            truck.apply(at(second), point(eastMeters, northMeters), 0);
        }
        return second - everySeconds;
    }

    /** Drives east at {@code metersPerSecond} starting from 0 m; returns the last reading's time. */
    private static long drive(Recorder truck, long fromSecond, long forSeconds, long everySeconds, double metersPerSecond) {
        return drive(truck, fromSecond, forSeconds, everySeconds, metersPerSecond, 0);
    }

    private static long drive(Recorder truck, long fromSecond, long forSeconds, long everySeconds,
                              double metersPerSecond, double fromEastMeters) {
        long second = fromSecond;
        for (; second < fromSecond + forSeconds; second += everySeconds) {
            double east = fromEastMeters + (second - fromSecond + everySeconds) * metersPerSecond;
            truck.apply(at(second), east(east), (int) Math.round(metersPerSecond * 3.6));
        }
        return second - everySeconds;
    }

    /** Drives in a straight line in {@code steps} readings 10 s apart; returns the last reading's time. */
    private static long driveTo(Recorder truck, long fromSecond, double fromEast, double fromNorth,
                                double toEast, double toNorth, int steps) {
        long second = fromSecond;
        for (int step = 1; step <= steps; step++, second += 10) {
            double fraction = (double) step / steps;
            truck.apply(at(second), point(fromEast + (toEast - fromEast) * fraction,
                    fromNorth + (toNorth - fromNorth) * fraction), 36);
        }
        return second - 10;
    }

    private static Instant at(long second) {
        return T0.plusSeconds(second);
    }

    private static double[] east(double meters) {
        return point(meters, 0);
    }

    private static double[] point(double eastMeters, double northMeters) {
        double metersPerDegree = 6_371_000 * Math.PI / 180;
        return new double[] {
                BASE_LATITUDE + northMeters / metersPerDegree,
                BASE_LONGITUDE + eastMeters / (metersPerDegree * Math.cos(Math.toRadians(BASE_LATITUDE)))};
    }

    private static DetectedTrip onlyTrip(Recorder truck) {
        assertEquals(1, truck.trips.size(), "trips: " + truck.trips.values());
        return truck.trips.values().iterator().next();
    }

    private static DetectedStop onlyStop(Recorder truck) {
        assertEquals(1, truck.stops.size(), "stops: " + truck.stops.values());
        return truck.stops.values().iterator().next();
    }

    private static double distanceBetweenEnds(DetectedTrip trip) {
        return GeoDistanceCalculator.distanceInMeters(trip.startLatitude(), trip.startLongitude(),
                trip.endLatitude(), trip.endLongitude());
    }

    private static WindowReading windowReadingOf(RawGpsFrame frame) {
        return new WindowReading(frame.recordedAt(), frame.latitude(), frame.longitude(), 0, 0);
    }

    /** Lines of runtime/payloads/osmand-sent-positions.jsonl from the traccar-rnd study, as raw frames (speed knots -> km/h). */
    private static List<RawGpsFrame> readTraccarDrive() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        List<RawGpsFrame> frames = new ArrayList<>();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                TripMotionProcessorTest.class.getResourceAsStream("/trip/osmand-sent-positions.jsonl")),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                JsonNode params = json.readTree(line).get("params");
                frames.add(new RawGpsFrame(
                        params.get("id").asString(),
                        Instant.ofEpochSecond(params.get("timestamp").asLong()),
                        Double.parseDouble(params.get("lat").asString()),
                        Double.parseDouble(params.get("lon").asString()),
                        (int) Math.round(params.get("speed").asDouble() * 1.852)));
            }
        }
        return frames;
    }

    /** Runs every frame through the real processing; returns the accepted positions by line number (1-based). */
    private static Map<Integer, ProcessedGpsPosition> processLikeThePipeline(List<RawGpsFrame> frames) {
        GpsFrameProcessor processor = new GpsFrameProcessor(new InMemoryPreviousReadingStore());
        Map<Integer, ProcessedGpsPosition> processedByLine = new LinkedHashMap<>();
        for (int index = 0; index < frames.size(); index++) {
            int line = index + 1;
            processor.process(GpsMessageJson.toJson(frames.get(index)), new ProcessingResultHandler() {
                @Override
                public void onAccepted(ProcessedGpsPosition position) {
                    processedByLine.put(line, position);
                }

                @Override
                public void onRejected(RejectedGpsFrame rejection) {
                    // duplicates: not passed on, like in the pipeline
                }
            });
        }
        return processedByLine;
    }
}
