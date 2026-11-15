package com.srm.gps.application.admin;

import java.time.Instant;

/**
 * Published (Spring application event) once the demo reset has emptied the telemetry tables and Redis: a module that
 * keeps copies in memory forgets them, so no stale state outlives the reset.
 */
public record TelemetryCleared(Instant at) {
}
