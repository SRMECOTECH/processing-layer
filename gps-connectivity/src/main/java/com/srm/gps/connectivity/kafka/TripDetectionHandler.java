package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

/** Whoever turns processed positions into trips and stops implements this (today: PostgreSQL). */
public interface TripDetectionHandler {

    void handle(ProcessedGpsPosition position);
}
