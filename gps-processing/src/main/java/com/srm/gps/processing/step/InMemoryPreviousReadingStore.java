package com.srm.gps.processing.step;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the newest reading of every device in memory: one small entry per device (150 vehicles = 150 entries).
 * Safe to use from several threads at once. Empty again after a restart.
 */
public class InMemoryPreviousReadingStore implements PreviousReadingStore {

    private final Map<String, PreviousReading> latestReadingByImei = new ConcurrentHashMap<>();

    @Override
    public Optional<PreviousReading> findLatestReading(String imei) {
        return Optional.ofNullable(latestReadingByImei.get(imei));
    }

    @Override
    public void rememberLatestReading(String imei, PreviousReading reading) {
        latestReadingByImei.put(imei, reading);
    }

    /** Forgets every device (demo reset). */
    public void forgetAll() {
        latestReadingByImei.clear();
    }

    /** What is held in memory for one device, without the database fallback a subclass may add. */
    public Optional<PreviousReading> findInMemory(String imei) {
        return Optional.ofNullable(latestReadingByImei.get(imei));
    }
}
