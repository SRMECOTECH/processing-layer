package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

/** Whoever keeps the latest position of every vehicle implements this (today: Redis). */
public interface LiveLocationHandler {

    void handle(ProcessedGpsPosition position);
}
