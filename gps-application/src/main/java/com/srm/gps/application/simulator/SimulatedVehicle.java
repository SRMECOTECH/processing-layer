package com.srm.gps.application.simulator;

import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsPing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One simulated device replaying one real smart_truck trip, forwards then backwards, with each ping keeping its
 * real time gap to the previous one ("faithful" timing).
 *
 * The device also gets what smart_truck does not record, so every consumer has something real to work on:
 *  - ignition (only some devices have the wire; the rest are left to virtual ignition): on while moving and just
 *    before moving off, kept on for the first two standing pings, then off - unless the driver is an "idler" who
 *    leaves the engine running while standing (these produce stoppage and idling alarms);
 *  - an odometer (only some devices): it grows by smart_truck's own per-ping distance (i_dist).
 */
final class SimulatedVehicle {

    private static final int ENGINE_KEPT_ON_STANDING_PINGS = 2;
    private static final double STANDING_LEG_METERS = 30;
    private static final Duration PAUSE_BETWEEN_DRIVES = Duration.ofSeconds(60);

    record Step(long offsetSeconds, double latitude, double longitude, int speedKmph, int legMeters, boolean ignition) {
    }

    private final String imei;
    private final List<SmartTruckGpsPing> trip;
    private final Instant firstDriveStart;
    private final boolean reportsIgnition;
    private final boolean reportsOdometer;
    private final boolean idler;

    private long odometerMeters;
    private int driveNumber;
    private Instant driveStart;
    private List<Step> steps;
    private int index;
    private Instant lastEmitted;

    SimulatedVehicle(List<SmartTruckGpsPing> trip, Instant firstDriveStart, boolean reportsIgnition, boolean reportsOdometer, boolean idler) {
        this.imei = trip.getFirst().deviceImei();
        this.trip = trip;
        this.reportsIgnition = reportsIgnition;
        this.reportsOdometer = reportsOdometer;
        this.idler = idler;
        this.odometerMeters = 40_000_000L + Math.floorMod(imei.hashCode(), 50_000_000);
        this.driveNumber = 1;
        this.driveStart = firstDriveStart;
        this.firstDriveStart = firstDriveStart;
        this.steps = plan(trip, false, idler);
    }

    String imei() {
        return imei;
    }

    /** When the first ping of the first drive is sent: the trip's first ping, moved to the replay clock. */
    Instant firstDriveStart() {
        return firstDriveStart;
    }

    /** When the next ping is due on the replay clock, or null when this drive is over. */
    Instant nextDue() {
        return index < steps.size() ? driveStart.plusSeconds(steps.get(index).offsetSeconds()) : null;
    }

    RawGpsFrame emit() {
        Step step = steps.get(index++);
        odometerMeters += step.legMeters();
        lastEmitted = driveStart.plusSeconds(step.offsetSeconds());
        return new RawGpsFrame(imei, lastEmitted, step.latitude(), step.longitude(), step.speedKmph(),
                reportsIgnition ? step.ignition() : null,
                reportsOdometer ? odometerMeters : null);
    }

    boolean driveFinished() {
        return index >= steps.size();
    }

    /** Drives the trip again in the other direction, after a short pause at the end. */
    void startNextDrive() {
        driveNumber++;
        driveStart = (lastEmitted != null ? lastEmitted : driveStart).plus(PAUSE_BETWEEN_DRIVES);
        steps = plan(trip, driveNumber % 2 == 0, idler);
        index = 1;   // the first point of the new drive is where the previous one ended
    }

    int driveNumber() {
        return driveNumber;
    }

    int index() {
        return index;
    }

    int stepsPerDrive() {
        return steps.size();
    }

    /** The drive's steps in driving order, with real time offsets, leg distances and the simulated ignition. */
    static List<Step> plan(List<SmartTruckGpsPing> trip, boolean backwards, boolean idler) {
        int n = trip.size();
        long[] offsets = new long[n];
        int[] legs = new int[n];
        SmartTruckGpsPing[] order = new SmartTruckGpsPing[n];
        for (int k = 0; k < n; k++) {
            int original = backwards ? n - 1 - k : k;
            order[k] = trip.get(original);
            offsets[k] = backwards
                    ? Duration.between(trip.get(original).recordedAt(), trip.getLast().recordedAt()).getSeconds()
                    : Duration.between(trip.getFirst().recordedAt(), trip.get(original).recordedAt()).getSeconds();
            // the leg into this point: forwards it is the point's own i_dist, backwards the next point's
            legs[k] = k == 0 ? 0 : backwards ? Math.max(0, trip.get(original + 1).distanceMeters()) : Math.max(0, order[k].distanceMeters());
        }

        boolean[] standing = new boolean[n];
        for (int k = 0; k < n; k++) {
            standing[k] = order[k].speedKmph() == 0 && legs[k] < STANDING_LEG_METERS;
        }
        List<Step> steps = new ArrayList<>(n);
        int standingRun = 0;
        for (int k = 0; k < n; k++) {
            standingRun = standing[k] ? standingRun + 1 : 0;
            boolean aboutToMove = k + 1 < n && !standing[k + 1];
            boolean ignition = !standing[k] || aboutToMove || standingRun <= ENGINE_KEPT_ON_STANDING_PINGS || idler;
            steps.add(new Step(offsets[k], order[k].latitude(), order[k].longitude(), order[k].speedKmph(), legs[k], ignition));
        }
        return steps;
    }
}
