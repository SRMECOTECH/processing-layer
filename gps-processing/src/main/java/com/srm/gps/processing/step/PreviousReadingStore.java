package com.srm.gps.processing.step;

import java.util.Optional;

/**
 * Remembers the newest reading of every device.
 *
 * {@link InMemoryPreviousReadingStore} keeps them in memory only.
 * The Spring service plugs in a store that also looks in the database after a restart,
 * so the first reading after a restart is still compared with the right previous one.
 */
public interface PreviousReadingStore {

    Optional<PreviousReading> findLatestReading(String imei);

    void rememberLatestReading(String imei, PreviousReading reading);
}
