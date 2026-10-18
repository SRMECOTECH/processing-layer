package com.srm.gps.configuration;

/**
 * The two ways a raw frame can travel through the service, chosen with the property {@value #PROPERTY}.
 *
 *   kafka  : frame -> Kafka -> processing consumer -> Kafka -> storage consumer -> PostgreSQL
 *            Buffered and decoupled: if PostgreSQL is down, frames wait safely in Kafka.
 *
 *   direct : frame -> processing -> PostgreSQL, in one method call, without Kafka.
 *            Simplest to run and debug, but nothing is buffered: if PostgreSQL is down, the frame fails.
 */
public final class ProcessingFlowNames {

    public static final String PROPERTY = "gps.processing-flow";

    public static final String KAFKA = "kafka";

    public static final String DIRECT = "direct";

    private ProcessingFlowNames() {
    }
}
