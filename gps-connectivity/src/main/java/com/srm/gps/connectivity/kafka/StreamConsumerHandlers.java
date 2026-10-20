package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

/**
 * The four stream consumers after Kafka. Each is implemented by a service in gps-application
 * and called by its own consumer group (Kafka flow) or directly (direct flow).
 */
public final class StreamConsumerHandlers {

    private StreamConsumerHandlers() {
    }

    /** gps-speed-violation-service: per-entity speed rule, sustained over-speed, speedometer vs GPS. */
    public interface SpeedViolationHandler {
        void handle(ProcessedGpsPosition position);
    }

    /** gps-vehicle-state-service: each vehicle's state and history in Redis. */
    public interface VehicleStateHandler {
        void handle(ProcessedGpsPosition position);
    }

    /** gps-stoppage-service: stoppages (engine on) and parking (engine off), with traffic/weather context. */
    public interface StoppageHandler {
        void handle(ProcessedGpsPosition position);
    }

    /** gps-ignition-service: ignition (device or virtual), engine hours, idling. */
    public interface IgnitionHandler {
        void handle(ProcessedGpsPosition position);
    }
}
