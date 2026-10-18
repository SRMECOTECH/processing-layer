package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

/**
 * Whoever wants to act on processed positions implements this (the storage service).
 * The connectivity module only receives the message; what happens next (storing it) is decided elsewhere.
 */
public interface ProcessedGpsPositionHandler {

    void handle(ProcessedGpsPosition position);
}
