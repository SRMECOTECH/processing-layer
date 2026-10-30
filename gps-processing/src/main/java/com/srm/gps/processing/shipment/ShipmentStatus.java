package com.srm.gps.processing.shipment;

import java.time.Duration;
import java.time.Instant;

/**
 * Where a shipment stands and whether it is on time - worked out when it is read, from the stored moments, so it is
 * always current and never stored twice. Pure function, no I/O.
 *
 *   stage    BOOKED      the truck has not passed the gate-out yet (dispatch in the future)
 *            AT_ORIGIN   past the gate-out but still in the plant fence: the hidden detention after the gate-out
 *            IN_TRANSIT  left the origin, not arrived
 *            ARRIVED     the GPS saw it enter the destination fence, or the consigner's TMS reported an arrival
 *            CLOSED      closed without an arrival (the next trip of the same truck was uploaded, or the TMS closed it)
 *   verdict  ON_TIME / LATE once arrived (against the ETA); before that ON_TRACK, AT_RISK (the estimated arrival
 *            is after the ETA) or DELAYED (the ETA has passed); NO_ETA when the file gave none
 *   gpsSilent  true while the shipment is on the road and its truck has not reported for GPS_SILENT_AFTER
 *
 * The estimated arrival is the newest position plus the straight-line distance to the destination times
 * ROAD_FACTOR, at the trip class's average speed: an estimate to rank risk, not a routed ETA.
 */
public final class ShipmentStatus {

    /** Roads are longer than the straight line: about 1.3 times on Indian highways and city roads. */
    public static final double ROAD_FACTOR = 1.3;
    /** Average truck speed used for the estimate: local trips crawl through city traffic. */
    public static final double LOCAL_SPEED_KMPH = 25;
    public static final double ZONAL_SPEED_KMPH = 40;
    public static final Duration GPS_SILENT_AFTER = Duration.ofMinutes(60);

    public enum Stage { BOOKED, AT_ORIGIN, IN_TRANSIT, ARRIVED, CLOSED }

    public enum Verdict { ON_TIME, LATE, ON_TRACK, AT_RISK, DELAYED, NO_ETA }

    /**
     * @param arrivedAt          the arrival used: the GPS one, otherwise the one the TMS reported
     * @param delayMinutes       arrival (or now, while on the road) minus the ETA; negative when early; null without an ETA
     * @param estimatedArrivalAt the estimate while on the road with a known destination and position, else null
     * @param remainingKm        estimated road distance still to go, else null
     * @param transitMinutes     departure to arrival (or to now while on the road), null before departure
     * @param originHoldMinutes  gate-out to departure from the origin fence (the "geofence tail"), null before departure
     */
    public record Evaluation(Stage stage, Verdict verdict, Instant arrivedAt, Long delayMinutes, Instant estimatedArrivalAt,
                             Double remainingKm, Long transitMinutes, Long originHoldMinutes, boolean gpsSilent) {
    }

    /** What is known about one shipment. */
    public record Facts(Instant dispatchedAt, Instant etaAt, Instant leftOriginAt, Instant gpsArrivedAt, Instant reportedArrivedAt,
                        Instant closedAt, boolean sourceClosed, Instant lastReadingAt, Double lastLatitude, Double lastLongitude,
                        Fence destination, boolean localTrip) {
    }

    private ShipmentStatus() {
    }

    public static Evaluation evaluate(Facts f, Instant now) {
        Instant arrivedAt = f.gpsArrivedAt() != null ? f.gpsArrivedAt() : f.reportedArrivedAt();
        Stage stage;
        if (arrivedAt != null) {
            stage = Stage.ARRIVED;
        } else if (f.closedAt() != null || f.sourceClosed()) {
            stage = Stage.CLOSED;
        } else if (f.dispatchedAt().isAfter(now)) {
            stage = Stage.BOOKED;
        } else if (f.leftOriginAt() != null) {
            stage = Stage.IN_TRANSIT;
        } else {
            stage = Stage.AT_ORIGIN;
        }
        boolean onTheRoad = stage == Stage.AT_ORIGIN || stage == Stage.IN_TRANSIT;

        Instant estimate = null;
        Double remainingKm = null;
        if (onTheRoad && f.destination() != null && f.lastLatitude() != null && f.lastLongitude() != null) {
            remainingKm = f.destination().contains(f.lastLatitude(), f.lastLongitude()) ? 0.0
                    : f.destination().distanceMeters(f.lastLatitude(), f.lastLongitude()) * ROAD_FACTOR / 1000.0;
            double speed = f.localTrip() ? LOCAL_SPEED_KMPH : ZONAL_SPEED_KMPH;
            Instant from = f.lastReadingAt() != null && f.lastReadingAt().isAfter(now) ? f.lastReadingAt() : now;
            estimate = from.plusSeconds(Math.round(remainingKm / speed * 3600));
        }

        Verdict verdict;
        Long delay = null;
        if (f.etaAt() == null) {
            verdict = Verdict.NO_ETA;
        } else if (arrivedAt != null) {
            delay = Duration.between(f.etaAt(), arrivedAt).toMinutes();
            verdict = arrivedAt.isAfter(f.etaAt()) ? Verdict.LATE : Verdict.ON_TIME;
        } else if (onTheRoad && now.isAfter(f.etaAt())) {
            delay = Duration.between(f.etaAt(), now).toMinutes();
            verdict = Verdict.DELAYED;
        } else if (onTheRoad && estimate != null && estimate.isAfter(f.etaAt())) {
            delay = Duration.between(f.etaAt(), estimate).toMinutes();
            verdict = Verdict.AT_RISK;
        } else {
            verdict = Verdict.ON_TRACK;
        }

        Long transit = null;
        Long originHold = null;
        if (f.leftOriginAt() != null) {
            Instant end = arrivedAt != null ? arrivedAt : (onTheRoad ? now : null);
            transit = end == null ? null : Math.max(0, Duration.between(f.leftOriginAt(), end).toMinutes());
            originHold = Math.max(0, Duration.between(f.dispatchedAt(), f.leftOriginAt()).toMinutes());
        }
        boolean silent = onTheRoad && !f.dispatchedAt().isAfter(now)
                && (f.lastReadingAt() == null ? Duration.between(f.dispatchedAt(), now).compareTo(GPS_SILENT_AFTER) > 0
                : Duration.between(f.lastReadingAt(), now).compareTo(GPS_SILENT_AFTER) > 0);
        return new Evaluation(stage, verdict, arrivedAt, delay, estimate, remainingKm, transit, originHold, silent);
    }
}
