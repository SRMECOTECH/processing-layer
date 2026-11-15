package com.srm.gps.application.tms;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.MovementStatus;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleContext;
import com.srm.gps.module.ModuleDescriptor;
import com.srm.gps.processing.shipment.Fence;
import com.srm.gps.processing.shipment.ShipmentProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Module shipment-tracking (consumer group gps-shipment-tracking-service): follows every shipment with the GPS of its
 * truck - the first module written against gps-module-api, and the example to copy for the next one.
 *
 * For each reading: the truck's shipments that the reading can still tell something about (ShipmentStore#trackable),
 * then the pure {@link ShipmentProgress} with the shipment's origin and destination geofences, then the new progress
 * and the events. Every moment kept is the earliest reading proving it, so the result is the same whatever order the
 * readings come in, however often - a redelivery, a device emptying its memory after a coverage gap, or a replay.
 *
 * Events (vehicle_event, Live -> Event stream, the portals' alerts):
 *   SHIPMENT_DEPARTED  the truck cleared the origin fence (or moved off, on an intra-fence trip)
 *   SHIPMENT_ARRIVED   it entered the destination fence
 *   SHIPMENT_DELAYED   the first reading after the ETA without an arrival opens it; the arrival closes it
 */
@Component
public class ShipmentTrackingModule implements GpsModule {

    private static final Logger LOG = LoggerFactory.getLogger(ShipmentTrackingModule.class);

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor("shipment-tracking", "Shipment tracking",
            "Follows every uploaded shipment with its truck's GPS: departure from the plant, arrival, delay against the ETA.",
            KafkaConsumerGroupNames.SHIPMENT_TRACKING, PipelineStage.SHIPMENT_TRACKING, 50,
            List.of("SHIPMENT_DEPARTED", "SHIPMENT_ARRIVED", "SHIPMENT_DELAYED"), List.of());

    private final ShipmentStore shipments;
    private final PlaceStore places;

    public ShipmentTrackingModule(ShipmentStore shipments, PlaceStore places) {
        this.shipments = shipments;
        this.places = places;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onPosition(ProcessedGpsPosition position, ModuleContext context) {
        ModuleContext.VehicleRef vehicle = context.vehicle();
        List<ShipmentStore.Stored> open = shipments.trackable(vehicle.registrationNo(), position.imei(), position.recordedAt());
        if (open.isEmpty()) {
            return;
        }
        ShipmentProgress.Reading reading = new ShipmentProgress.Reading(position.recordedAt(), position.latitude(),
                position.longitude(), position.speedKmph(), position.movementStatus() == MovementStatus.MOVING);
        for (ShipmentStore.Stored s : open) {
            Fence origin = places.byId(s.originPlaceId()).map(PlaceStore.Place::fence).orElse(null);
            Fence destination = places.byId(s.destinationPlaceId()).map(PlaceStore.Place::fence).orElse(null);
            ShipmentProgress before = s.progress();
            ShipmentProgress after = ShipmentProgress.apply(before, s.dispatchedAt(), origin, destination, reading);
            if (after.equals(before)) {
                continue;
            }
            // events first, then the progress: when anything fails, the reading comes again and finds the same events
            if (!Objects.equals(before.leftOriginAt(), after.leftOriginAt())) {
                context.emit(event(vehicle, s, VehicleEventType.SHIPMENT_DEPARTED, EventSeverity.INFO, VehicleEvent.Status.CLOSED,
                        after.leftOriginAt(), after.leftOriginAt(), position));
            }
            if (!Objects.equals(before.arrivedAt(), after.arrivedAt())) {
                context.emit(event(vehicle, s, VehicleEventType.SHIPMENT_ARRIVED, EventSeverity.INFO, VehicleEvent.Status.CLOSED,
                        after.arrivedAt(), after.arrivedAt(), position));
                if (s.etaAt() != null && after.arrivedAt().isAfter(s.etaAt())) {
                    context.emit(event(vehicle, s, VehicleEventType.SHIPMENT_DELAYED, EventSeverity.HIGH, VehicleEvent.Status.CLOSED,
                            s.etaAt(), after.arrivedAt(), position));
                }
            } else if (after.arrivedAt() == null && s.etaAt() != null && position.recordedAt().isAfter(s.etaAt())
                    && (before.lastReadingAt() == null || !before.lastReadingAt().isAfter(s.etaAt()))) {
                context.emit(event(vehicle, s, VehicleEventType.SHIPMENT_DELAYED, EventSeverity.HIGH, VehicleEvent.Status.OPEN,
                        s.etaAt(), null, position));
            }
            shipments.saveProgress(s.id(), after);
        }
    }

    @Override
    public void beforeReplay(Instant from) {
        int cleared = shipments.clearProgressSince(from);
        LOG.warn("Shipment tracking replay from {}: GPS progress of {} shipments cleared, to be worked out again", from, cleared);
    }

    private static VehicleEvent event(ModuleContext.VehicleRef vehicle, ShipmentStore.Stored s, VehicleEventType type,
                                      EventSeverity severity, VehicleEvent.Status status, Instant startedAt, Instant endedAt,
                                      ProcessedGpsPosition at) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("shipmentId", s.id());
        details.put("tripNo", s.tripNo());
        details.put("origin", s.origin());
        details.put("destination", s.destination());
        details.put("consignee", s.consignee());
        details.put("transporter", s.transporterName());
        details.put("consigner", s.consignerUnit());
        if (s.etaAt() != null) {
            details.put("etaAt", s.etaAt().toString());
        }
        // the shipment's truck, even when the device is matched through the IMEI in the file
        ModuleContext.VehicleRef truck = vehicle.mapped() ? vehicle : new ModuleContext.VehicleRef(vehicle.imei(), s.vehicleNo(), null);
        return truck.event(type, severity, status, startedAt, endedAt, at.latitude(), at.longitude(), PipelineStage.SHIPMENT_TRACKING.name(), details);
    }
}
