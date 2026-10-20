package com.srm.gps.processing.ignition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ignition, engine hours and idling for one device. Pure: state + reading in, new state + findings out.
 *
 * Ignition source:
 *   DEVICE  - the frame carries the ignition wire (Traccar IgnitionEventHandler: an event on each edge);
 *   VIRTUAL - no wire, so it is inferred from motion, the way trackers without an ignition input do it:
 *             moving means the engine is on; standing longer than virtualOffAfterSeconds means it is off.
 *             (A towed vehicle would read as "on" - the known limit of motion-based virtual ignition.)
 * A change must persist for a few readings (debounce) before it counts, and it is back-dated to when it was
 * first seen; a virtual OFF is back-dated to when the vehicle stopped.
 *
 * Engine hours: like Traccar EngineHoursHandler, time between two readings is credited when the engine was on at
 * both, but never more than maxCountedGapSeconds for one gap.
 *
 * Idling: engine on, at most idleSpeedKmph and not moving. Raised once it lasts idleAlertSeconds; closed with
 * its duration when the vehicle moves off or the engine stops.
 */
public final class IgnitionProcessor {

    private IgnitionProcessor() {
    }

    public record Update(IgnitionState state, List<IgnitionFinding> findings) {
    }

    public static Update update(IgnitionState s, IgnitionReading r, IgnitionSettings cfg) {
        if (r.arrivedOutOfOrder() || (s.lastRecordedAt() != null && !r.recordedAt().isAfter(s.lastRecordedAt()))) {
            return new Update(s, List.of());
        }
        List<IgnitionFinding> findings = new ArrayList<>();
        long dt = s.lastRecordedAt() == null ? 0 : Duration.between(s.lastRecordedAt(), r.recordedAt()).getSeconds();
        boolean moving = r.speedKmph() >= cfg.moveSpeedKmph() || r.distanceMeters() >= cfg.moveDistanceMeters();
        Instant standingSince = moving ? null : (s.standingSince() != null ? s.standingSince() : r.recordedAt());

        String source;
        Boolean observed;
        if (r.ignition() != null) {
            source = IgnitionState.DEVICE;
            observed = r.ignition();
        } else {
            source = IgnitionState.VIRTUAL;
            if (moving) {
                observed = true;
            } else if (Duration.between(standingSince, r.recordedAt()).getSeconds() >= cfg.virtualOffAfterSeconds()) {
                observed = false;
            } else {
                observed = s.ignitionOn() != null ? s.ignitionOn() : null;
            }
        }

        Boolean on = s.ignitionOn();
        String currentSource = s.source();
        Instant changedAt = s.changedAt();
        Boolean pendingValue = null;
        int pendingReadings = 0;
        Instant pendingSince = null;
        long onCount = s.ignitionOnCount();

        if (observed != null) {
            if (on == null) {
                on = observed;
                currentSource = source;
                changedAt = r.recordedAt();
                onCount += observed ? 1 : 0;
            } else if (!observed.equals(on)) {
                boolean samePending = observed.equals(s.pendingValue());
                pendingValue = observed;
                pendingReadings = samePending ? s.pendingReadings() + 1 : 1;
                pendingSince = samePending ? s.pendingSince() : r.recordedAt();
                int required = IgnitionState.DEVICE.equals(source) ? cfg.deviceDebounceReadings() : cfg.virtualDebounceReadings();
                if (pendingReadings >= required) {
                    Instant changeTime = IgnitionState.VIRTUAL.equals(source) && !observed && standingSince != null
                            ? standingSince : pendingSince;
                    if (changedAt != null && changeTime.isBefore(changedAt)) {
                        changeTime = changedAt;
                    }
                    long previousSeconds = changedAt == null ? 0 : Duration.between(changedAt, changeTime).getSeconds();
                    findings.add(new IgnitionFinding(observed ? IgnitionFinding.Kind.IGNITION_ON : IgnitionFinding.Kind.IGNITION_OFF,
                            changeTime, null, r.latitude(), r.longitude(), source, previousSeconds, 0));
                    on = observed;
                    currentSource = source;
                    changedAt = changeTime;
                    onCount += observed ? 1 : 0;
                    pendingValue = null;
                    pendingReadings = 0;
                    pendingSince = null;
                }
            } else {
                currentSource = source;
            }
        }

        long engineSeconds = s.engineSeconds()
                + (Boolean.TRUE.equals(s.ignitionOn()) && Boolean.TRUE.equals(on) ? Math.min(dt, cfg.maxCountedGapSeconds()) : 0);

        Instant idleSince = s.idleSince();
        boolean idleAlerted = s.idleAlerted();
        long idleTotal = s.idleSecondsTotal();
        boolean idling = Boolean.TRUE.equals(on) && !moving && r.speedKmph() <= cfg.idleSpeedKmph();
        if (idling) {
            if (idleSince == null) {
                idleSince = r.recordedAt();
            }
            if (!idleAlerted && Duration.between(idleSince, r.recordedAt()).getSeconds() >= cfg.idleAlertSeconds()) {
                findings.add(new IgnitionFinding(IgnitionFinding.Kind.IDLING_STARTED, idleSince, null, r.latitude(), r.longitude(),
                        currentSource, 0, Duration.between(idleSince, r.recordedAt()).getSeconds()));
                idleAlerted = true;
            }
        } else if (idleSince != null) {
            Instant idleEnd = s.lastRecordedAt() != null ? s.lastRecordedAt() : r.recordedAt();
            long duration = Math.max(0, Duration.between(idleSince, idleEnd).getSeconds());
            if (idleAlerted) {
                findings.add(new IgnitionFinding(IgnitionFinding.Kind.IDLING_ENDED, idleSince, idleEnd, s.lastLatitude(),
                        s.lastLongitude(), currentSource, 0, duration));
            }
            idleTotal += duration;
            idleSince = null;
            idleAlerted = false;
        }

        return new Update(new IgnitionState(on, currentSource, changedAt, pendingValue, pendingReadings, pendingSince,
                engineSeconds, idleSince, idleAlerted, idleTotal, standingSince, r.recordedAt(), r.latitude(), r.longitude(),
                onCount), findings);
    }
}
