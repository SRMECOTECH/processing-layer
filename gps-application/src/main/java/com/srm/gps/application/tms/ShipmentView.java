package com.srm.gps.application.tms;

import com.srm.gps.application.tms.PlaceStore.Place;
import com.srm.gps.processing.shipment.ShipmentStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * A shipment as the portals show it: the file's facts, what the GPS proved, and where it stands now (worked out at
 * read time by {@link ShipmentStatus}, so it is always current).
 *
 * @param gpsLink          LINKED (a device is mapped to the truck), IMEI_MISMATCH (mapped, but the file names another
 *                         IMEI), FILE_IMEI (no device mapped; followed through the file's IMEI), NO_DEVICE (not followed)
 * @param worksDetentionMinutes  booking (plant entry) to gate-out: the detention the TMS declares
 * @param originHoldMinutes      gate-out to clearing the plant fence: the hidden tail after the gate-out
 */
public record ShipmentView(
        long id, String tripNo, String source,
        String transporterCode, String transporterName, String consignerCode, String consignerName, String consignerUnit,
        String consignee, String consigneeAddress,
        String vehicleNo, String vehicleType, String deviceImei, String mappedImei, String gpsLink,
        String tripClass, String tripType, String origin, String destination, String loadPlant,
        Fence originFence, Fence destinationFence,
        Instant bookedAt, Instant dispatchedAt, Instant etaAt, Instant reportedAtaAt,
        String invoiceNo, String shipmentRef, String material, BigDecimal quantity, String driverName, String driverMobile,
        String sourceStatus, String closeReason,
        Instant leftOriginAt, Instant gpsArrivedAt, Instant arrivedAt, Instant lastReadingAt, Double lastLatitude,
        Double lastLongitude, Integer lastSpeedKmph, Instant closedAt, String closedReason,
        String stage, String verdict, Long delayMinutes, Instant estimatedArrivalAt, Double remainingKm, Long transitMinutes,
        Long originHoldMinutes, Long worksDetentionMinutes, boolean gpsSilent, Instant updatedAt) {

    public record Fence(String name, double latitude, double longitude, int radiusMeters) {
        static Fence of(Place place) {
            return place == null ? null : new Fence(place.name(), place.latitude(), place.longitude(), place.radiusMeters());
        }
    }

    public boolean onTheRoad() {
        return "AT_ORIGIN".equals(stage) || "IN_TRANSIT".equals(stage);
    }

    static ShipmentView of(ShipmentStore.Stored s, Place origin, Place destination, Instant now) {
        ShipmentStatus.Evaluation e = ShipmentStatus.evaluate(new ShipmentStatus.Facts(s.dispatchedAt(), s.etaAt(), s.leftOriginAt(),
                s.arrivedAt(), s.reportedAtaAt(), s.closedAt(), s.sourceClosed(), s.lastReadingAt(), s.lastLatitude(),
                s.lastLongitude(), destination == null ? null : destination.fence(),
                s.tripClass() != null && s.tripClass().equalsIgnoreCase("local")), now);
        String link;
        if (s.mappedImei() != null) {
            link = s.deviceImei() == null || ShipmentImportService.sameImei(s.mappedImei(), s.deviceImei()) ? "LINKED" : "IMEI_MISMATCH";
        } else {
            link = s.deviceImei() != null ? "FILE_IMEI" : "NO_DEVICE";
        }
        Long works = s.bookedAt() != null && s.dispatchedAt() != null && !s.bookedAt().isAfter(s.dispatchedAt())
                ? Duration.between(s.bookedAt(), s.dispatchedAt()).toMinutes() : null;
        return new ShipmentView(s.id(), s.tripNo(), s.source(), s.transporterCode(), s.transporterName(), s.consignerCode(),
                s.consignerName(), s.consignerUnit(), s.consignee(), s.consigneeAddress(), s.vehicleNo(), s.vehicleType(),
                s.deviceImei(), s.mappedImei(), link, s.tripClass(), s.tripType(), s.origin(), s.destination(), s.loadPlant(),
                Fence.of(origin), Fence.of(destination), s.bookedAt(), s.dispatchedAt(), s.etaAt(), s.reportedAtaAt(),
                s.invoiceNo(), s.shipmentRef(), s.material(), s.quantity(), s.driverName(), s.driverMobile(), s.sourceStatus(),
                s.closeReason(), s.leftOriginAt(), s.arrivedAt(), e.arrivedAt(), s.lastReadingAt(), s.lastLatitude(),
                s.lastLongitude(), s.lastSpeedKmph(), s.closedAt(), s.closedReason(), e.stage().name(), e.verdict().name(),
                e.delayMinutes(), e.estimatedArrivalAt(), e.remainingKm() == null ? null : Math.round(e.remainingKm() * 10) / 10.0,
                e.transitMinutes(), e.originHoldMinutes(), works, e.gpsSilent(), s.updatedAt());
    }
}
