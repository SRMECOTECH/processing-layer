package com.srm.gps.streams;

/**
 * Lets the service count what the topologies do (engine dashboard load counters) without the topologies knowing
 * about Spring or Redis. Called on the stream thread for every record, so implementations must be cheap.
 * Under exactly-once a record can be processed again after an aborted transaction, so counts are "at least".
 */
public interface StreamsObserver {

    StreamsObserver NONE = new StreamsObserver() {
    };

    /**
     * @param imei      the frame's IMEI, null when it could not be read
     * @param partition the input partition
     * @param accepted  true for a processed position, false for a rejection
     */
    default void frameNormalized(String imei, int partition, boolean accepted) {
    }

    /** A processed position reached the analytics. */
    default void positionAnalyzed(String imei, int partition) {
    }
}
