package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.message.RejectedGpsFrame;

/** Whoever wants to act on rejected frames implements this (today: write them to the log). */
public interface RejectedGpsFrameHandler {

    void handle(RejectedGpsFrame rejection);
}
