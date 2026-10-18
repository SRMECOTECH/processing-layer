package com.srm.gps.application.simulator;

import com.srm.gps.application.flow.RawGpsFrameSender;
import com.srm.gps.application.masterdata.SimulationMasterDataSeeder;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsPing;
import com.srm.gps.connectivity.smarttruck.SmartTruckGpsReader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pretends to be a fleet of GPS devices, all driving at the same time.
 *
 * 1. Picks the newest trip of N different devices in smart_truck (read-only) - N = 150 by default.
 * 2. Registers those devices and vehicles in the new database (master data).
 * 3. Drives them in ROUNDS: round 1 = ping 1 of every vehicle, round 2 = ping 2 of every vehicle ...
 *    with a pause between rounds - exactly how a real fleet reports: everyone, every few seconds.
 *
 * Each frame goes into the {@link RawGpsFrameSender}, so the simulator works with both flows.
 * Later the real device parser will send frames the same way and this simulator can be switched off.
 */
@Service
public class GpsDeviceSimulatorService {

    private static final Logger LOG = LoggerFactory.getLogger(GpsDeviceSimulatorService.class);

    private final SmartTruckGpsReader smartTruckGpsReader;
    private final RawGpsFrameSender rawGpsFrameSender;
    private final SimulationMasterDataSeeder masterDataSeeder;
    private final GpsDeviceSimulatorSettings settings;

    private final ExecutorService simulatorThread = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "gps-device-simulator"));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong framesSent = new AtomicLong();
    private volatile int vehicleCount;
    private volatile int round;
    private volatile int roundsPerDrive;
    private volatile int driveNumber;
    private volatile String lastMessage = "Not started yet";

    public GpsDeviceSimulatorService(SmartTruckGpsReader smartTruckGpsReader,
                                     RawGpsFrameSender rawGpsFrameSender,
                                     SimulationMasterDataSeeder masterDataSeeder,
                                     GpsDeviceSimulatorSettings settings) {
        this.smartTruckGpsReader = smartTruckGpsReader;
        this.rawGpsFrameSender = rawGpsFrameSender;
        this.masterDataSeeder = masterDataSeeder;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAutomaticallyIfConfigured() {
        if (settings.startWhenApplicationStarts()) {
            startFleet(settings.vehicleCount(), settings.pingsPerVehicle());
        }
    }

    /** Starts the fleet in the background. Does nothing if it is already running. */
    public SimulationStatus startFleet(int requestedVehicleCount, int pingsPerVehicle) {
        if (!running.compareAndSet(false, true)) {
            return currentStatus();
        }
        framesSent.set(0);
        round = 0;
        driveNumber = 0;
        lastMessage = "Starting: picking " + requestedVehicleCount + " vehicles from smart_truck";
        simulatorThread.submit(() -> driveFleet(requestedVehicleCount, pingsPerVehicle));
        return currentStatus();
    }

    /** Asks a running fleet to stop after the current frame. */
    public SimulationStatus stopFleet() {
        if (running.get()) {
            running.set(false);
            lastMessage = "Stopped on request after " + framesSent.get() + " frames";
        }
        return currentStatus();
    }

    /** Sends one frame exactly as given - handy to see how the processing treats a broken frame. */
    public void sendSingleFrame(RawGpsFrame frame) {
        rawGpsFrameSender.send(frame);
    }

    public SimulationStatus currentStatus() {
        return new SimulationStatus(rawGpsFrameSender.flowName(), running.get(), vehicleCount, framesSent.get(),
                round, roundsPerDrive, driveNumber, settings.replayAsLive(), lastMessage);
    }

    private void driveFleet(int requestedVehicleCount, int pingsPerVehicle) {
        try {
            List<List<SmartTruckGpsPing>> tripOfEachVehicle = readTrips(requestedVehicleCount, pingsPerVehicle);
            vehicleCount = tripOfEachVehicle.size();
            roundsPerDrive = tripOfEachVehicle.stream().mapToInt(List::size).max().orElse(0);

            List<SmartTruckGpsPing> allPings = tripOfEachVehicle.stream().flatMap(List::stream).toList();
            masterDataSeeder.registerVehiclesAndDevices(allPings);
            LOG.info("Simulator: {} vehicles ready, {} pings, {} rounds per drive, one round every {} ms",
                    vehicleCount, allPings.size(), roundsPerDrive, settings.delayBetweenRoundsMillis());

            boolean keepDriving = true;
            while (keepDriving && running.get()) {
                driveNumber++;
                boolean drivingBack = driveNumber % 2 == 0;
                driveOnce(tripOfEachVehicle, drivingBack);
                keepDriving = settings.repeatForever() && settings.replayAsLive();
            }

            if (running.get()) {
                lastMessage = "Finished: sent " + framesSent.get() + " frames from " + vehicleCount + " vehicles";
            }
            LOG.info("Simulator: {}", lastMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            lastMessage = "Interrupted after " + framesSent.get() + " frames";
        } catch (Exception exception) {
            LOG.error("Simulator failed", exception);
            lastMessage = "Failed: " + exception.getMessage();
        } finally {
            running.set(false);
        }
    }

    private List<List<SmartTruckGpsPing>> readTrips(int requestedVehicleCount, int pingsPerVehicle) {
        List<Long> tripNumbers = smartTruckGpsReader.findLatestTripOfDistinctDevices(requestedVehicleCount, settings.minimumPingsPerTrip());
        List<List<SmartTruckGpsPing>> trips = new ArrayList<>();
        for (Long tripNumber : tripNumbers) {
            List<SmartTruckGpsPing> pings = smartTruckGpsReader.readPingsOfTrip(tripNumber, pingsPerVehicle);
            if (!pings.isEmpty()) {
                trips.add(pings);
            }
        }
        return trips;
    }

    /** One drive through every trip; driving back = the trip in reverse, so a vehicle never "jumps" to its start. */
    private void driveOnce(List<List<SmartTruckGpsPing>> tripOfEachVehicle, boolean drivingBack) throws InterruptedException {
        for (int roundIndex = 0; roundIndex < roundsPerDrive && running.get(); roundIndex++) {
            round = roundIndex + 1;
            Instant roundTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            for (List<SmartTruckGpsPing> trip : tripOfEachVehicle) {
                if (roundIndex >= trip.size() || !running.get()) {
                    continue;
                }
                SmartTruckGpsPing ping = trip.get(drivingBack ? trip.size() - 1 - roundIndex : roundIndex);
                rawGpsFrameSender.send(toRawFrame(ping, settings.replayAsLive() ? roundTime : ping.recordedAt()));
                framesSent.incrementAndGet();
            }
            lastMessage = "Driving: drive " + driveNumber + (drivingBack ? " (back)" : "") + ", round " + round + " of " + roundsPerDrive;
            Thread.sleep(settings.delayBetweenRoundsMillis());
        }
    }

    /** A device only knows its IMEI, position, speed and the time - so that is all the frame carries. */
    private static RawGpsFrame toRawFrame(SmartTruckGpsPing ping, Instant recordedAt) {
        return new RawGpsFrame(ping.deviceImei(), recordedAt, ping.latitude(), ping.longitude(), ping.speedKmph());
    }

    @PreDestroy
    public void shutDown() {
        running.set(false);
        simulatorThread.shutdownNow();
    }
}
