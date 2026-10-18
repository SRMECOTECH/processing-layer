package com.srm.gps.application.flow;

import com.srm.gps.configuration.message.RawGpsFrame;

/**
 * The entry door of the service: hand a raw frame in here and it will be processed and stored.
 * The simulator only talks to this interface, so it does not care which flow is active.
 *
 * Exactly one implementation exists at a time, chosen by gps.processing-flow:
 *   kafka  -> {@link com.srm.gps.application.flow.kafka.KafkaRawGpsFrameSender}
 *   direct -> {@link com.srm.gps.application.flow.direct.DirectProcessingFlow}
 */
public interface RawGpsFrameSender {

    void send(RawGpsFrame frame);

    /** "kafka" or "direct", for status messages. */
    String flowName();
}
