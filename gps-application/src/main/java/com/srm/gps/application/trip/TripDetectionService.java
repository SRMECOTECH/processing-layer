package com.srm.gps.application.trip;

import com.srm.gps.application.masterdata.DeviceVehicleDirectory;
import com.srm.gps.application.masterdata.DeviceVehicleDirectory.VehicleOfDevice;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.kafka.TripDetectionHandler;
import com.srm.gps.processing.trip.MotionEvent;
import com.srm.gps.processing.trip.MotionState;
import com.srm.gps.processing.trip.MotionUpdate;
import com.srm.gps.processing.trip.TripDetectionSettings;
import com.srm.gps.processing.trip.TripMotionProcessor;
import com.srm.gps.processing.trip.TripReading;
import com.srm.gps.processing.trip.VehicleAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Turns processed positions into trips and stops (gps_trip, gps_stop).
 * Kafka flow: consumer group "gps-trip-service". Direct flow: called directly.
 *
 * Per reading, ONE transaction:
 *   1. read the device's motion state
 *   2. a reading not newer than the last one applied is skipped (a late one is only counted)
 *   3. the pure {@link TripMotionProcessor} works out the new state and the trips/stops that changed
 *   4. save the state, guarded by "last_recorded_at < this reading" - 0 rows means another delivery
 *      already applied it, so the transaction is rolled back and nothing else is written
 *   5. upsert the trips and stops
 * A crash anywhere rolls everything back, and Kafka delivers the reading again.
 */
@Service
public class TripDetectionService implements TripDetectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TripDetectionService.class);

    private final VehicleMotionStateStore motionStateStore;
    private final TripAndStopStore tripAndStopStore;
    private final DeviceVehicleDirectory deviceVehicleDirectory;
    private final TripDetectionSettings settings;

    public TripDetectionService(VehicleMotionStateStore motionStateStore,
                                TripAndStopStore tripAndStopStore,
                                DeviceVehicleDirectory deviceVehicleDirectory,
                                TripDetectionProperties properties) {
        this.motionStateStore = motionStateStore;
        this.tripAndStopStore = tripAndStopStore;
        this.deviceVehicleDirectory = deviceVehicleDirectory;
        this.settings = properties.toSettings();
    }

    @Override
    @Transactional
    public void handle(ProcessedGpsPosition position) {
        String imei = position.imei();
        MotionState state = motionStateStore.find(imei).orElseGet(() -> MotionState.unknownDevice(imei));

        if (!state.neverSeen() && !position.recordedAt().isAfter(state.lastRecordedAt())) {
            // Our own bookmark decides, not the processing's arrivedOutOfOrder flag; the flag only tells
            // a late reading (counted once) from a Kafka redelivery of one already applied (ignored).
            if (position.arrivedOutOfOrder() && tripAndStopStore.noteLateReading(imei, position.recordedAt())) {
                LOG.info("Late reading at {} counted on its trip; the trip's distance is not changed", position.recordedAt());
            } else {
                LOG.debug("Skipping reading at {}: not newer than {}", position.recordedAt(), state.lastRecordedAt());
            }
            return;
        }

        VehicleAssignment assignment = deviceVehicleDirectory.findVehicleAt(imei, position.recordedAt())
                .map(TripDetectionService::toAssignment)
                .orElse(null);
        MotionUpdate update = TripMotionProcessor.updateState(state, TripReading.from(position, assignment), settings);

        if (!motionStateStore.saveIfNewer(update.state())) {
            LOG.debug("Reading at {} was already applied by another delivery - nothing written", position.recordedAt());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return;
        }
        update.trips().forEach(trip -> tripAndStopStore.saveTrip(imei, trip));
        update.stops().forEach(stop -> tripAndStopStore.saveStop(imei, stop));

        for (MotionEvent event : update.events()) {
            LOG.info("Vehicle {} {} at {}", assignment == null ? "(no vehicle)" : assignment.vehicleRegistrationNo(),
                    event.type() == MotionEvent.Type.MOVING ? "started a trip" : "stopped", event.recordedAt());
        }
    }

    private static VehicleAssignment toAssignment(VehicleOfDevice vehicle) {
        return new VehicleAssignment(vehicle.assignmentId().toString(), vehicle.tenantCode(), vehicle.vehicleRegistrationNo());
    }
}
