// Route plans, live ETA and waypoints (modules route-deviation and waypoints): the shapes /api/routes/* returns.

import type { Tone } from "@/components/ops/kit";

export type RouteStatus = "WAITING" | "ON_ROUTE" | "SUSPECT" | "OFF_ROUTE" | "ARRIVED";
export type StopStatus = "PENDING" | "INSIDE" | "ARRIVED" | "DEPARTED" | "PASSED" | "MISSED";

export type TripStop = {
  seq: number;
  name: string;
  kind: "MANUAL" | "FENCE";
  placeId: number | null;
  latitude: number;
  longitude: number;
  status: StopStatus;
  stayMinutes: number;
  arrivedAt: string | null;
  departedAt: string | null;
  missedAt: string | null;
  minutesInside: number | null;
  outOfOrder: boolean;
  /** 0..1 along the whole trip, null when the current line does not pass it */
  progress: number | null;
  km: number | null;
  plannedAt?: string | null;
  etaAt?: string | null;
};

export type Trip = {
  planId: number;
  status: "ACTIVE" | "ARRIVED" | "CANCELLED" | "CLOSED";
  routeStatus: RouteStatus;
  vehicle: string;
  imei: string | null;
  tenant: string | null;
  source: "SHIPMENT" | "MANUAL";
  shipmentId: number | null;
  tripNo: string | null;
  consignee: string | null;
  origin: string | null;
  destination: string | null;
  originLat: number;
  originLon: number;
  destinationLat: number;
  destinationLon: number;
  engine: string;
  version: number;
  plannedStartAt: string;
  plannedKm: number;
  plannedMinutes: number;
  doneKm: number;
  remainingKm: number;
  progress: number;
  crossTrackMeters: number | null;
  pace: number;
  departedAt: string | null;
  etaAt: string | null;
  promisedEtaAt: string | null;
  arrivedAt: string | null;
  lateMinutes: number | null;
  deviationOpen: boolean;
  deviationAddedMinutes: number | null;
  wrongWay: boolean;
  deviations: number;
  deviationKm: number;
  deviationMinutes: number;
  wrongWays: number;
  alternates: number;
  lastReadingAt: string | null;
  lastLat: number | null;
  lastLon: number | null;
  silentMinutes: number;
  noSignal: boolean;
  stops: TripStop[];
};

export type RouteEvent = {
  type: string;
  severity: "INFO" | "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  status: "OPEN" | "CLOSED";
  startedAt: string;
  endedAt: string | null;
  latitude: number;
  longitude: number;
  vehicle?: string;
  imei?: string;
  details: Record<string, unknown>;
};

/** [latitude, longitude, recordedAt, speedKmph, offTheLine] */
export type TrailPoint = [number, number, string, number, boolean];

export type TripDetail = Trip & {
  line: [number, number][];
  firstLine?: [number, number][];
  trail: TrailPoint[];
  events: RouteEvent[];
};

export type LiveRoutes = {
  generatedAt: string;
  counts: Record<"WAITING" | "ON_ROUTE" | "SUSPECT" | "OFF_ROUTE" | "WRONG_WAY" | "ARRIVED" | "LATE" | "NO_SIGNAL", number>;
  averageLateMinutes: number | null;
  trips: Trip[];
};

export type EngineState = { url: string | null; role: string; up: boolean; version?: string; problem?: string; millis?: number; profiles?: string[] };
export type Engines = { valhalla: EngineState; graphhopper: EngineState; costing: string };

export type RoutePreview = {
  engine: string;
  costing: string;
  lengthKm: number;
  minutes: number;
  millis: number;
  points: [number, number][];
  legs: { km: number; minutes: number }[];
  geofencesOnTheWay: { placeId: number; name: string; role: string; km: number }[];
};

export const STATUS_LABEL: Record<RouteStatus, string> = {
  WAITING: "Not left yet",
  ON_ROUTE: "On route",
  SUSPECT: "Checking",
  OFF_ROUTE: "Off route",
  ARRIVED: "Arrived",
};

export function tripTone(t: Pick<Trip, "routeStatus" | "wrongWay" | "noSignal">): Tone {
  if (t.wrongWay || t.routeStatus === "OFF_ROUTE") return "bad";
  if (t.routeStatus === "SUSPECT" || t.noSignal) return "warn";
  if (t.routeStatus === "ARRIVED" || t.routeStatus === "ON_ROUTE") return "good";
  return "neutral";
}

export function tripLabel(t: Pick<Trip, "routeStatus" | "wrongWay" | "noSignal">): string {
  if (t.wrongWay) return "Wrong direction";
  if (t.noSignal && t.routeStatus !== "ARRIVED") return `${STATUS_LABEL[t.routeStatus]} · no signal`;
  return STATUS_LABEL[t.routeStatus];
}

export const STOP_LABEL: Record<StopStatus, string> = {
  PENDING: "Ahead",
  INSIDE: "Inside",
  ARRIVED: "At stop",
  DEPARTED: "Visited",
  PASSED: "Drove through",
  MISSED: "Missed",
};

export function stopTone(s: StopStatus): Tone {
  if (s === "MISSED") return "bad";
  if (s === "ARRIVED" || s === "INSIDE") return "accent";
  if (s === "DEPARTED" || s === "PASSED") return "good";
  return "neutral";
}

/** "on time", "12 min late", "1 h 05 min early" against the promised ETA. */
export function lateText(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "–";
  const m = Math.abs(minutes);
  if (m < 5) return "on time";
  const text = m >= 60 ? `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")} min` : `${m} min`;
  return minutes > 0 ? `${text} late` : `${text} early`;
}

export function lateTone(minutes: number | null | undefined): Tone {
  if (minutes === null || minutes === undefined) return "neutral";
  if (minutes >= 60) return "bad";
  if (minutes >= 15) return "warn";
  return "good";
}

export function minutesText(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return "–";
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")} min`;
}

/** 14:20, or "Tue 14:20" when not today. */
export function clock(iso: string | null | undefined): string {
  if (!iso) return "–";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "–";
  const time = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false });
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return time;
  return `${d.toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short" })} ${time}`;
}

export const ROUTE_EVENT_LABEL: Record<string, string> = {
  ROUTE_DEVIATION: "Off route",
  ROUTE_ALTERNATE: "Alternate road",
  WRONG_DIRECTION: "Wrong direction",
  ETA_CHANGED: "ETA changed",
  DESTINATION_REACHED: "Destination reached",
  WAYPOINT_ARRIVED: "At stop",
  WAYPOINT_DEPARTED: "Left stop",
  WAYPOINT_MISSED: "Stop missed",
};

/** One line about what an event says, from its details. */
export function eventSummary(e: RouteEvent): string {
  const d = e.details;
  const n = (k: string) => (typeof d[k] === "number" ? (d[k] as number) : undefined);
  switch (e.type) {
    case "ROUTE_DEVIATION":
      return e.status === "OPEN"
        ? `Off the planned road, ${n("offKm") ?? 0} km so far; this way adds about ${minutesText(n("addedMinutes") ?? 0)}${d.verdict === "UNVERIFIED" ? " (routing engine did not answer)" : ""}`
        : `Back on route after ${minutesText(n("minutesOff") ?? 0)}: ${n("kmOff") ?? 0} km off the plan, ${n("extraKm") ?? 0} km extra`;
    case "ROUTE_ALTERNATE":
      return `Took another road about as good (${minutesText(n("addedMinutes") ?? 0)} more); the plan follows it now`;
    case "WRONG_DIRECTION":
      return d.onPlan === false
        ? `Driving away from the destination (${n("awayKm") ?? "?"} km further)`
        : e.status === "OPEN"
          ? `Driving back along the route, ${n("backKm") ?? "?"} km lost`
          : `Forward again after ${minutesText(n("minutes") ?? 0)} (${n("backKm") ?? "?"} km back)`;
    case "ETA_CHANGED":
      return `ETA ${clock(d.from as string)} → ${clock(d.to as string)} (${(n("changeMinutes") ?? 0) > 0 ? "+" : ""}${minutesText(n("changeMinutes") ?? 0)}) · ${String(d.reason ?? "").toLowerCase()} · ${lateText(n("lateMinutes"))}`;
    case "DESTINATION_REACHED":
      return `Arrived ${lateText(n("lateMinutes"))} against the promise; ${n("deviations") ?? 0} deviations, ${n("deviationKm") ?? 0} km extra`;
    case "WAYPOINT_ARRIVED":
      return `${d.stop}${d.outOfOrder ? " (out of order)" : ""}${e.endedAt ? `, stayed ${minutesText(Math.round((new Date(e.endedAt).getTime() - new Date(e.startedAt).getTime()) / 60_000))}` : ""}`;
    case "WAYPOINT_DEPARTED":
      return `Left ${d.stop} after ${minutesText(n("stayMinutes") ?? 0)}${n("plannedStayMinutes") ? ` (planned ${n("plannedStayMinutes")} min)` : ""}`;
    case "WAYPOINT_MISSED":
      return `${d.stop} never visited${d.reason === "AT_DESTINATION" ? " before the destination" : ""}`;
    default:
      return "";
  }
}
