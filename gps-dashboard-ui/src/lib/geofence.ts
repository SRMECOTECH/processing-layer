// The geofencing module's API (/api/geofences/**, /api/portal/geofence-report) as the dashboard reads it.

import type { Tone } from "@/components/ops/kit";

export type FenceRole = "ORIGIN" | "DESTINATION" | "YARD" | "CHECKPOINT" | "ZONE";

export type Fence = {
  id: number;
  code: string;
  name: string;
  role: FenceRole;
  shape: "CIRCLE" | "POLYGON";
  polygon: [number, number][] | null;
  latitude: number;
  longitude: number;
  radiusMeters: number;
  dwellAlertMinutes: number | null;
  dwellAlertMinutesInForce: number | null;
  speedLimitKmph: number | null;
  source: string;
  sourceDetail: string | null;
  externalRef: string | null;
  tripsSeen: number | null;
  spreadMeters: number | null;
  active: boolean;
  updatedAt: string;
  insideNow: number;
};

export type Visit = {
  id: number;
  imei: string;
  vehicleRegistrationNo: string | null;
  tenantCode: string | null;
  placeId: number;
  placeCode: string;
  placeName: string;
  placeRole: FenceRole;
  kind: "VISIT" | "PASS";
  enteredAt: string;
  exitedAt: string | null;
  entryGapSeconds: number | null;
  exitGapSeconds: number | null;
  latitude: number;
  longitude: number;
  dwellAlertAt: string | null;
  dwellLimitMinutes: number | null;
  inferred: boolean;
  exitReason: string | null;
  /** Where the stay ends for counting: the exit, or the device's last reading while open. */
  until: string;
  /** Open visits: minutes since the device last reported. */
  silentMinutes: number;
  minutes: number;
};

/** Open and silent this long: "inside when last seen", not "inside now" (the service's rule). */
export const SILENT_AFTER_MINUTES = 30;

export type GeofenceEngine = {
  index: { fences: number; filledCells: number; cellDegrees: number; cellKmApprox: number; maxFencesPerCell: number; averageFencesPerFilledCell: number };
  rules: {
    confirmMinutes: number;
    escapeFactor: number;
    passMinTravelMeters: number;
    crossingMaxGapMinutes: number;
    crossingMinMoveMeters: number;
    defaultDwellAlertMinutes: number;
    dwellAlertMinutesByRole: Record<string, number>;
  };
};

export type TimelineSegment = {
  fenceId: number;
  name: string;
  role: FenceRole;
  enteredAt: string;
  exitedAt: string | null;
  startMinute: number;
  endMinute: number;
  stayMinutes: number;
  open: boolean;
  noSignalMinutes: number;
  overLimit: boolean;
  startedBefore: boolean;
  uncertainMinutes: number;
};

export type DayReportFence = {
  fenceId: number;
  code: string;
  name: string;
  role: FenceRole;
  visits: number;
  vehicles: number;
  minutesInside: number;
  medianStayMinutes: number | null;
  p90StayMinutes: number | null;
  longestStayMinutes: number | null;
  longestVehicle: string | null;
  stillInside: number;
  insideWhenLastSeen: number;
  longStays: number;
  passes: number;
  dwellLimitMinutes: number | null;
  usualVisits: number;
  usualMedianMinutes: number | null;
  usualP90Minutes: number | null;
  vsUsual: number | null;
};

export type DayReportVehicle = {
  imei: string;
  vehicleRegistrationNo: string | null;
  tenantCode: string | null;
  visits: number;
  passes: number;
  minutesInside: number;
  minutesByRole: Partial<Record<FenceRole, number>>;
  longestStay: { name: string; minutes: number };
  stillInside: string[];
  silentMinutes: number;
  day: { readings: number; distanceKm: number; movingMinutes: number; stoppedMinutes: number; maxSpeedKmph: number; overspeedEvents: number } | null;
  timeline: TimelineSegment[];
};

export type Insight = { severity: "HIGH" | "MEDIUM" | "INFO"; text: string; fenceId: number | null; imei: string | null };

export type GeofenceDayReport = {
  date: string;
  zone: string;
  from: string;
  to: string;
  partial: boolean;
  generatedAt: string;
  headline: {
    visits: number;
    passes: number;
    vehicles: number;
    fencesVisited: number;
    minutesInside: number;
    medianStayMinutes: number | null;
    longStays: number;
    stillInside: number;
    insideWhenLastSeen: number;
    uncertainStamps: number;
  };
  fences: DayReportFence[];
  occupancy: { fenceId: number; name: string; role: FenceRole; averageInsideByHour: number[]; peakHour: number; peakAverageInside: number }[];
  vehicles: DayReportVehicle[];
  insights: Insight[];
  method: Record<string, unknown>;
};

export const ROLE_LABEL: Record<FenceRole, string> = {
  ORIGIN: "Plant / origin",
  DESTINATION: "Customer / destination",
  YARD: "Yard",
  CHECKPOINT: "Checkpoint",
  ZONE: "Zone",
};

export function roleTone(role: FenceRole): Tone {
  return role === "ORIGIN" ? "accent" : role === "DESTINATION" ? "good" : role === "YARD" ? "warn" : "neutral";
}

/** "42 min", "3 h 05 min", "2 d 4 h". */
export function stay(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "–";
  const m = Math.max(0, Math.round(minutes));
  if (m < 60) return `${m} min`;
  const d = Math.floor(m / 1440);
  const h = Math.floor((m % 1440) / 60);
  if (d > 0) return `${d} d ${h} h`;
  return `${h} h ${String(m % 60).padStart(2, "0")} min`;
}

export function vehicleOf(v: { vehicleRegistrationNo: string | null; imei: string }): string {
  return v.vehicleRegistrationNo ?? `Device ${v.imei}`;
}
