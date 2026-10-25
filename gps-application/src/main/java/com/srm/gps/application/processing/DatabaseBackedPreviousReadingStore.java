package com.srm.gps.application.processing;

import com.srm.gps.connectivity.database.repository.GpsPositionRepository;
import com.srm.gps.processing.step.InMemoryPreviousReadingStore;
import com.srm.gps.processing.step.PreviousReading;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The newest reading of every device, kept in memory for speed.
 *
 * After a restart the memory is empty, so for a device seen for the first time since then
 * it looks up the newest reading already stored in PostgreSQL. That way the first reading
 * after a restart still gets the right distance and time gap instead of 0.
 */
@Component
public class DatabaseBackedPreviousReadingStore extends InMemoryPreviousReadingStore {

    private final GpsPositionRepository gpsPositionRepository;

    public DatabaseBackedPreviousReadingStore(GpsPositionRepository gpsPositionRepository) {
        this.gpsPositionRepository = gpsPositionRepository;
    }

    @Override
    public Optional<PreviousReading> findLatestReading(String imei) {
        Optional<PreviousReading> inMemory = super.findLatestReading(imei);
        if (inMemory.isPresent()) {
            return inMemory;
        }

        Optional<PreviousReading> fromDatabase = findStoredReading(imei);
        fromDatabase.ifPresent(reading -> rememberLatestReading(imei, reading));
        return fromDatabase;
    }

    /**
     * Only the newest reading stored in PostgreSQL, without touching memory. The Kafka Streams normalizer uses this
     * for a device its state store has never seen, so after the hand-over from the listener distances carry on.
     */
    public Optional<PreviousReading> findStoredReading(String imei) {
        return gpsPositionRepository.findFirstByImeiOrderByRecordedAtDesc(imei)
                .map(stored -> new PreviousReading(stored.getRecordedAt(), stored.getLatitude(), stored.getLongitude(),
                        stored.getTotalDistanceMeters() == null ? 0 : stored.getTotalDistanceMeters(),
                        stored.getOdometerMeters()));
    }
}
