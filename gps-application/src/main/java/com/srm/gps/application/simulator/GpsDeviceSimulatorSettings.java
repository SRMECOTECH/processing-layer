package com.srm.gps.application.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the GPS device simulator (properties starting with "gps.simulator").
 *
 * @param vehicleCount               how many different vehicles (devices) drive at the same time
 * @param pingsPerVehicle            how many pings of each vehicle's trip to replay
 * @param minimumPingsPerTrip        only pick trips that have at least this many pings
 * @param delayBetweenRoundsMillis   one "round" = one ping from every vehicle; pause between rounds.
 *                                   150 vehicles every 2000 ms = 75 frames per second.
 * @param replayAsLive               true: stamp each frame with the current time, as if the trucks were driving now.
 *                                   false: keep the original smart_truck times (a second replay is then all duplicates).
 * @param repeatForever              true: when the trips end, drive them back again, and so on, so data keeps coming.
 *                                   Only used together with replayAsLive.
 * @param startWhenApplicationStarts start replaying automatically when the service starts
 */
@ConfigurationProperties(prefix = "gps.simulator")
public record GpsDeviceSimulatorSettings(
        int vehicleCount,
        int pingsPerVehicle,
        int minimumPingsPerTrip,
        long delayBetweenRoundsMillis,
        boolean replayAsLive,
        boolean repeatForever,
        boolean startWhenApplicationStarts) {
}
