package com.srm.gps.application.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the GPS device simulator (properties starting with "gps.simulator").
 *
 * @param vehicleCount               how many different vehicles (devices) drive at the same time
 * @param pingsPerVehicle            how many pings of each vehicle's trip to replay
 * @param minimumPingsPerTrip        only pick trips that have at least this many pings
 * @param delayBetweenRoundsMillis   compressed timing only: one "round" = one ping from every vehicle; pause between rounds
 * @param replayAsLive               true: re-stamp the trips around the current time, as if the trucks were driving now.
 *                                   false: keep the original smart_truck times (a second replay is then all duplicates).
 * @param repeatForever              true: when the trips end, drive them back again, and so on, so data keeps coming
 * @param startWhenApplicationStarts start replaying automatically when the service starts
 * @param timing                     "faithful" (default): every ping keeps its real gap to the previous one, so speeds from
 *                                   distance over time, durations and stops are real; the replay starts catchUpMinutes in
 *                                   the past, runs catchUpSpeedFactor times faster until it reaches now, then in real time.
 *                                   "compressed": the old behaviour - one round of pings every delayBetweenRoundsMillis,
 *                                   stamped now (fast, but a minute of driving lasts 2 seconds).
 * @param catchUpMinutes             faithful timing: how far in the past the replay starts
 * @param catchUpSpeedFactor         faithful timing: how much faster than real time until it has caught up
 * @param devicesWithIgnition        share (0..1) of devices that report an ignition wire; the rest get virtual ignition
 * @param devicesWithOdometer        share (0..1) of devices that report an odometer; the rest are measured by GPS
 * @param idlingDevices              share (0..1) of devices whose drivers leave the engine running while standing
 */
@ConfigurationProperties(prefix = "gps.simulator")
public record GpsDeviceSimulatorSettings(
        int vehicleCount,
        int pingsPerVehicle,
        int minimumPingsPerTrip,
        long delayBetweenRoundsMillis,
        boolean replayAsLive,
        boolean repeatForever,
        boolean startWhenApplicationStarts,
        String timing,
        Integer catchUpMinutes,
        Integer catchUpSpeedFactor,
        Double devicesWithIgnition,
        Double devicesWithOdometer,
        Double idlingDevices) {

    public boolean faithfulTiming() {
        return timing == null || timing.isBlank() || "faithful".equalsIgnoreCase(timing);
    }

    public int catchUpMinutesOrDefault() {
        return catchUpMinutes != null ? catchUpMinutes : 120;
    }

    public int catchUpSpeedFactorOrDefault() {
        return catchUpSpeedFactor != null ? Math.max(1, catchUpSpeedFactor) : 30;
    }

    public double devicesWithIgnitionOrDefault() {
        return devicesWithIgnition != null ? devicesWithIgnition : 0.5;
    }

    public double devicesWithOdometerOrDefault() {
        return devicesWithOdometer != null ? devicesWithOdometer : 0.5;
    }

    public double idlingDevicesOrDefault() {
        return idlingDevices != null ? idlingDevices : 0.25;
    }
}
