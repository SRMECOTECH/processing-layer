package com.srm.gps.application.simulator;

/**
 * What the simulator is doing right now.
 *
 * @param processingFlow  "kafka" or "direct": the flow the frames are sent into
 * @param running         true while frames are being sent
 * @param vehicleCount    vehicles driving in the current (or last) run
 * @param framesSent      frames sent in the current (or last) run
 * @param round           current round (one ping per vehicle per round), counted from 1
 * @param roundsPerDrive  rounds in one drive through the trips
 * @param driveNumber     1 = first drive forwards, 2 = driving back, 3 = forwards again ...
 * @param replayAsLive    frames are stamped with the current time
 * @param lastMessage     plain-English summary of the last thing that happened
 */
public record SimulationStatus(
        String processingFlow,
        boolean running,
        int vehicleCount,
        long framesSent,
        int round,
        int roundsPerDrive,
        int driveNumber,
        boolean replayAsLive,
        String lastMessage) {
}
