package com.srm.gps.module;

import com.srm.gps.configuration.message.ProcessedGpsPosition;

import java.time.Instant;

/**
 * One processing module after Kafka: it sees every processed position of every vehicle, in order per vehicle.
 *
 * To plug a module in:
 *   1. implement this interface (the logic itself belongs in a plain, unit-tested class, like the processors in
 *      gps-processing), and make it a Spring bean (@Component) on the service's class path;
 *   2. switch it on or off with one line: gps.modules.&lt;id&gt;.enabled (on unless set to false).
 *
 * The platform then gives it, without any code in the module:
 *   - its own consumer group ({@link ModuleDescriptor#consumerGroup()}) on gps.processed-positions, so it has its
 *     own bookmark and a slow module never holds up another; one thread per partition, so one vehicle's readings
 *     reach {@link #onPosition} one at a time and in order;
 *   - no data loss: a failure caused by PostgreSQL, Redis or Kafka being down is retried, for ever, and the reading
 *     waits in Kafka; a reading the module cannot handle at all goes to gps.dead-letters, from where it is replayed;
 *   - replay: the module's bookmark can be moved back to any moment Kafka still holds (31 days), see
 *     {@link #beforeReplay(Instant)};
 *   - load counters, and a row in the module registry (GET /api/modules, Developer -> Module registry).
 *
 * Because a reading can arrive twice (a redelivery or a replay) and late (a device that was out of coverage sends
 * its stored readings when it is back), {@link #onPosition} must be idempotent: write events through
 * {@link ModuleContext#emit} (one row per episode, keyed by vehicle, type and start) and keep state keyed by the
 * reading's own time, never by the moment it arrived.
 */
public interface GpsModule {

    ModuleDescriptor descriptor();

    /**
     * Handles one reading. Throwing means "not done": the reading is handed over again (infrastructure failures
     * for ever, anything else three times and then it is parked as a dead letter).
     */
    void onPosition(ProcessedGpsPosition position, ModuleContext context);

    /**
     * Called before the module's bookmark is moved back to {@code from} and the readings since then are handed over
     * again, with the module's consumers stopped. A module whose state would make the replayed readings look old
     * clears that state here; the platform has already deleted the Redis keys of {@link ModuleDescriptor#statePatterns()}.
     */
    default void beforeReplay(Instant from) {
    }
}
