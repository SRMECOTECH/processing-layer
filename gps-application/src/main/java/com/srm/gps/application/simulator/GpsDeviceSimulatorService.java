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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * 3. Replays them. Two timings:
 *    faithful (default) - every ping keeps its real gap to the previous one (about a minute in smart_truck), so speed
 *        from distance over time, the 60-second over-speed rule, 3-minute stops and idling all behave as on the road.
 *        The replay clock starts catchUpMinutes in the past and runs catchUpSpeedFactor times faster until it has
 *        caught up with now (a quick burst of history), then continues in real time - timestamps never lie in the
 *        future and never go backwards.
 *    compressed - the original behaviour: one round of pings (one per vehicle) every delayBetweenRoundsMillis,
 *        stamped now. Fast, but a minute of driving lasts two seconds, so time-based rules cannot work.
 *
 * Each frame goes into the {@link RawGpsFrameSender}, so the simulator works with both flows.
 * Later the real device parser will send frames the same way and this simulator can be switched off.
 */
@Service
public class GpsDeviceSimulatorService {

    private static final Logger LOG = LoggerFactory.getLogger(GpsDeviceSimulatorService.class);
    private static final long TICK_MILLIS = 200;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final SmartTruckGpsReader smartTruckGpsReader;
    private final RawGpsFrameSender rawGpsFrameSender;
    private final SimulationMasterDataSeeder masterDataSeeder;
    private final GpsDeviceSimulatorSettings settings;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
                                     GpsDeviceSimulatorSettings settings,
                                     org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.smartTruckGpsReader = smartTruckGpsReader;
        this.rawGpsFrameSender = rawGpsFrameSender;
        this.masterDataSeeder = masterDataSeeder;
        this.settings = settings;
        this.jdbc = jdbc;
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

            if (settings.replayAsLive() && settings.faithfulTiming()) {
                driveFaithfully(tripOfEachVehicle);
            } else {
                driveCompressed(tripOfEachVehicle);
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
            if (pings.size() >= 2) {
                trips.add(pings);
            }
        }
        return trips;
    }

    // ---------------------------------------------------------------- faithful timing

    private void driveFaithfully(List<List<SmartTruckGpsPing>> trips) throws InterruptedException {
        Instant wallStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant replayStart = wallStart.minus(Duration.ofMinutes(settings.catchUpMinutesOrDefault()));
        // Never replay into time already stored: those readings would all count as late. After "Empty DB" the
        // database is empty again and the full catch-up runs.
        Instant newestStored = newestStoredReading();
        if (newestStored != null && newestStored.plusSeconds(60).isAfter(replayStart)) {
            replayStart = newestStored.plusSeconds(60).isAfter(wallStart) ? wallStart : newestStored.plusSeconds(60);
        }
        int speedFactor = settings.catchUpSpeedFactorOrDefault();

        List<SimulatedVehicle> vehicles = new ArrayList<>();
        for (List<SmartTruckGpsPing> trip : trips) {
            String imei = trip.getFirst().deviceImei();
            vehicles.add(new SimulatedVehicle(trip, replayStart,
                    share(imei, "ignition") < settings.devicesWithIgnitionOrDefault(),
                    share(imei, "odometer") < settings.devicesWithOdometerOrDefault(),
                    share(imei, "idler") < settings.idlingDevicesOrDefault()));
        }
        LOG.info("Simulator (faithful timing): {} vehicles, replay starts {} min in the past at {}x until it catches up",
                vehicles.size(), settings.catchUpMinutesOrDefault(), speedFactor);

        boolean anyDriving = true;
        while (running.get() && anyDriving) {
            Instant now = Instant.now();
            Instant replayed = replayStart.plusMillis(Duration.between(wallStart, now).toMillis() * speedFactor);
            boolean live = !replayed.isBefore(now);
            Instant clock = live ? now : replayed;

            anyDriving = false;
            int maxIndex = 0;
            int maxDrive = 0;
            for (SimulatedVehicle vehicle : vehicles) {
                Instant due = vehicle.nextDue();
                while (due != null && !due.isAfter(clock) && running.get()) {
                    rawGpsFrameSender.send(vehicle.emit());
                    framesSent.incrementAndGet();
                    if (vehicle.driveFinished() && settings.repeatForever()) {
                        vehicle.startNextDrive();
                    }
                    due = vehicle.nextDue();
                }
                anyDriving |= due != null;
                maxIndex = Math.max(maxIndex, vehicle.index());
                maxDrive = Math.max(maxDrive, vehicle.driveNumber());
            }
            round = maxIndex;
            driveNumber = maxDrive;
            lastMessage = live
                    ? "Live: driving in real time, drive " + maxDrive + ", ping " + maxIndex + " of " + roundsPerDrive
                    : "Catching up: replay clock " + CLOCK.format(clock) + " (" + Duration.between(clock, now).toMinutes()
                    + " min behind, " + speedFactor + "x)";
            Thread.sleep(TICK_MILLIS);
        }
    }

    private Instant newestStoredReading() {
        try {
            java.sql.Timestamp newest = jdbc.queryForObject("SELECT max(recorded_at) FROM gps_position", java.sql.Timestamp.class);
            return newest == null ? null : newest.toInstant();
        } catch (RuntimeException databaseProblem) {
            LOG.warn("Could not read the newest stored reading: {}", databaseProblem.getMessage());
            return null;
        }
    }

    /** A stable number in [0, 1) per device and purpose, so the same devices always have a wire, an odometer ... */
    private static double share(String imei, String purpose) {
        return Math.floorMod((imei + ":" + purpose).hashCode(), 1000) / 1000.0;
    }

    // ---------------------------------------------------------------- compressed timing (original behaviour)

    private void driveCompressed(List<List<SmartTruckGpsPing>> tripOfEachVehicle) throws InterruptedException {
        LOG.info("Simulator (compressed timing): {} vehicles, {} rounds per drive, one round every {} ms",
                vehicleCount, roundsPerDrive, settings.delayBetweenRoundsMillis());
        boolean keepDriving = true;
        while (keepDriving && running.get()) {
            driveNumber++;
            boolean drivingBack = driveNumber % 2 == 0;
            driveOnce(tripOfEachVehicle, drivingBack);
            keepDriving = settings.repeatForever() && settings.replayAsLive();
        }
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
            lastMessage = "Driving (compressed): drive " + driveNumber + (drivingBack ? " (back)" : "") + ", round " + round + " of " + roundsPerDrive;
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
