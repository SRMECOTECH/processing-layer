package com.srm.gps.connectivity.kafka;

/**
 * Whoever wants to act on raw device frames implements this (the processing service).
 * The frame is passed as the JSON text that arrived, so even a broken frame reaches the processing
 * and is rejected there with a clear reason.
 */
public interface RawGpsFrameHandler {

    void handle(String rawFrameJson);
}
