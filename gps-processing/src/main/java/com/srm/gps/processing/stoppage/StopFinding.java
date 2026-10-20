package com.srm.gps.processing.stoppage;

import com.srm.gps.processing.stoppage.StoppageState.Kind;

import java.time.Instant;

/**
 * A stop starting or ending.
 *
 * @param kind        STOPPAGE (engine on: alarm, gets traffic/weather context) or PARKED (engine off: neutralised)
 * @param started     true when the stop starts (event OPEN), false when it ends (CLOSED)
 * @param startedAt   first reading of the stop (back-dated to when the vehicle arrived)
 * @param endedAt     last reading inside the stop, null while it lasts
 * @param merged      true when this stop continues the previous one (same place, short break)
 */
public record StopFinding(Kind kind, boolean started, Instant startedAt, Instant endedAt, double latitude, double longitude,
                          long durationSeconds, long engineOnSeconds, int readings, Boolean ignition,
                          String ignitionSource, boolean merged) {
}
