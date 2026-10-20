import type { GpsPositionView } from "./types";

/** How far apart the speedometer and the distance-over-time speed may be before a reading is flagged. */
export const SPEED_TOLERANCE_KMPH = 10;

export type Verdict = "VERIFIED" | "DUAL_FAIL" | "STOPPED" | "FIRST";

/** Speed worked out from the distance and time since the vehicle's previous reading (Haversine distance). */
export function calculatedSpeedKmph(reading: GpsPositionView): number | null {
  if (reading.secondsSincePreviousReading <= 0) return null;
  return (reading.distanceFromPreviousMeters / reading.secondsSincePreviousReading) * 3.6;
}

/** Dual verification of one stored reading: speedometer against calculated speed. */
export function verify(reading: GpsPositionView): { verdict: Verdict; calculated: number | null; variance: number | null } {
  const calculated = calculatedSpeedKmph(reading);
  if (calculated === null) return { verdict: "FIRST", calculated, variance: null };
  const variance = reading.speedKmph - calculated;
  if (reading.movementStatus === "STOPPED" && reading.speedKmph === 0) return { verdict: "STOPPED", calculated, variance };
  return { verdict: Math.abs(variance) <= SPEED_TOLERANCE_KMPH ? "VERIFIED" : "DUAL_FAIL", calculated, variance };
}
