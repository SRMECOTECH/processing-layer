package com.srm.gps.streams.normalize;

import com.srm.gps.processing.step.PreviousReading;

import java.util.Optional;

/**
 * Where the normalizer looks when its state store has never seen a device: after the hand-over from the listener,
 * or after a reset. The service passes the newest position stored in PostgreSQL, so the first reading is still
 * measured from the right place and the running total distance carries on instead of starting from 0.
 * The answer is written to the store (and its changelog), so each device is looked up at most once.
 */
@FunctionalInterface
public interface PreviousReadingFallback {

    /** No fallback: an unknown device starts from its first reading (tests, shadow mode). */
    PreviousReadingFallback NONE = imei -> Optional.empty();

    Optional<PreviousReading> find(String imei);
}
