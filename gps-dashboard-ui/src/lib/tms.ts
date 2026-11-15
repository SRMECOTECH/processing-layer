// The transport management (TMS) side of the console: shapes of the /api/portal answers, labels and tones.
// The service decides whose data an answer holds (the token's role and company); these pages only show it.

import type { Tone } from "@/components/ops/kit";
import type { VehicleEventView } from "./types";

export type Stage = "BOOKED" | "AT_ORIGIN" | "IN_TRANSIT" | "ARRIVED" | "CLOSED";
export type Verdict = "ON_TIME" | "LATE" | "ON_TRACK" | "AT_RISK" | "DELAYED" | "NO_ETA";
export type GpsLink = "LINKED" | "IMEI_MISMATCH" | "FILE_IMEI" | "NO_DEVICE";

export type Fence = { name: string; latitude: number; longitude: number; radiusMeters: number };

export type Shipment = {
  id: number;
  tripNo: string;
  source: string;
  transporterCode: string;
  transporterName: string;
  consignerCode: string | null;
  consignerName: string | null;
  consignerUnit: string | null;
  consignee: string | null;
  consigneeAddress: string | null;
  vehicleNo: string;
  vehicleType: string | null;
  deviceImei: string | null;
  mappedImei: string | null;
  gpsLink: GpsLink;
  tripClass: string | null;
  tripType: string | null;
  origin: string | null;
  destination: string | null;
  loadPlant: string | null;
  originFence: Fence | null;
  destinationFence: Fence | null;
  bookedAt: string | null;
  dispatchedAt: string;
  etaAt: string | null;
  reportedAtaAt: string | null;
  invoiceNo: string | null;
  shipmentRef: string | null;
  material: string | null;
  quantity: number | null;
  driverName: string | null;
  driverMobile: string | null;
  sourceStatus: string | null;
  closeReason: string | null;
  leftOriginAt: string | null;
  gpsArrivedAt: string | null;
  arrivedAt: string | null;
  lastReadingAt: string | null;
  lastLatitude: number | null;
  lastLongitude: number | null;
  lastSpeedKmph: number | null;
  closedAt: string | null;
  closedReason: string | null;
  stage: Stage;
  verdict: Verdict;
  delayMinutes: number | null;
  estimatedArrivalAt: string | null;
  remainingKm: number | null;
  transitMinutes: number | null;
  originHoldMinutes: number | null;
  worksDetentionMinutes: number | null;
  gpsSilent: boolean;
  updatedAt: string;
};

export type TrackPoint = { recorded_at: string; latitude: number; longitude: number; speed_kmph: number };

export type ShipmentDetail = {
  shipment: Shipment;
  track: TrackPoint[];
  trackFrom: string;
  trackTo: string;
  events: VehicleEventView[];
  timeline: { at: string; kind: string; label: string }[];
};

export type RowProblem = { row: number; tripNo: string | null; message: string; blocking: boolean };
export type MappingHint = { vehicleNo: string; imeiInFile: string | null; mappedImei: string | null; state: string };

export type ImportReport = {
  importId: number | null;
  fileName: string;
  format: string;
  layout: string;
  columns: Record<string, string>;
  ignoredColumns: string[];
  missingRequired: string[];
  rowsTotal: number;
  rowsValid: number;
  rowsFailed: number;
  created: number;
  updated: number;
  unchanged: number;
  closedByNextUpload: number;
  problems: RowProblem[];
  unknownPlaces: Record<string, number>;
  newCompanies: string[];
  mappingHints: MappingHint[];
  importedBefore: string | null;
  sample: Record<string, string | number | null>[];
  committed: boolean;
};

export type ShipmentKpis = {
  total: number;
  active: number;
  booked: number;
  atOrigin: number;
  inTransit: number;
  arrivedToday: number;
  arrived: number;
  closedWithoutArrival: number;
  delayed: number;
  atRisk: number;
  gpsSilent: number;
  onTimePct: number | null;
  onTimeBasis: number;
  avgDelayMinutesWhenLate: number | null;
  avgTransitHours: number | null;
  avgWorksDetentionMinutes: number | null;
  avgOriginHoldMinutes: number | null;
  gpsCoveragePct: number | null;
  noDevice: number;
  imeiMismatch: number;
  quantityOnTheRoad: number;
};

export type Performance = {
  shipments: number;
  active: number;
  delayed: number;
  arrived: number;
  onTimePct: number | null;
  avgTransitHours: number | null;
  avgDelayMinutesWhenLate: number | null;
};

export type FleetState = "MOVING" | "STANDING" | "OFFLINE" | "NEVER_REPORTED" | "NO_DEVICE";

export type FleetRow = {
  vehicleNo: string;
  ownerCode: string | null;
  ownerName: string | null;
  imei: string | null;
  state: FleetState;
  lastReadingAt: string | null;
  latitude: number | null;
  longitude: number | null;
  speedKmph: number | null;
  shipment: { id: number; tripNo: string; destination: string; stage: Stage; verdict: Verdict } | null;
  today: { km: number; overspeedEvents: number; harshEvents: number; idleSeconds: number; engineOnStops: number } | null;
};

export type Overview = {
  role: string;
  company: string | null;
  periodDays: number;
  generatedAt: string;
  shipments: ShipmentKpis;
  attention: Shipment[];
  lanes?: (Performance & { lane: string })[];
  transporters?: (Performance & { code: string; name: string; trucks: number; gpsCoveragePct: number; overspeedEvents: number })[];
  consignees?: (Performance & { consignee: string })[];
  plants?: { plant: string; shipments: number; avgWorksDetentionMinutes: number | null; avgOriginHoldMinutes: number | null; atOriginNow: number }[];
  fleet?: {
    vehicles: number;
    withDevice: number;
    moving: number;
    standing: number;
    offline: number;
    never_reported: number;
    no_device: number;
    onShipment: number;
    utilisationPct: number | null;
  };
  today?: { km: number; overspeedEvents: number; harshEvents: number; idleHours: number; engineOnStops: number };
  platform?: Record<string, number>;
  recentAlerts: VehicleEventView[];
};

export type Company = { id: string; code: string; name: string; kind: "GPS_PROVIDER" | "TRANSPORTER" | "CONSIGNER"; externalCode: string | null };

export const STAGE_LABEL: Record<Stage, string> = {
  BOOKED: "Booked",
  AT_ORIGIN: "At origin",
  IN_TRANSIT: "In transit",
  ARRIVED: "Arrived",
  CLOSED: "Closed",
};

export const VERDICT_LABEL: Record<Verdict, string> = {
  ON_TIME: "On time",
  LATE: "Late",
  ON_TRACK: "On track",
  AT_RISK: "At risk",
  DELAYED: "Delayed",
  NO_ETA: "No ETA",
};

export const GPS_LINK_LABEL: Record<GpsLink, string> = {
  LINKED: "GPS linked",
  IMEI_MISMATCH: "IMEI differs",
  FILE_IMEI: "File IMEI",
  NO_DEVICE: "No device",
};

export const FLEET_STATE_LABEL: Record<FleetState, string> = {
  MOVING: "Moving",
  STANDING: "Standing",
  OFFLINE: "Offline",
  NEVER_REPORTED: "Never reported",
  NO_DEVICE: "No device",
};

export function stageTone(stage: Stage): Tone {
  return stage === "IN_TRANSIT" ? "accent" : stage === "ARRIVED" ? "good" : stage === "AT_ORIGIN" ? "warn" : "neutral";
}

export function verdictTone(verdict: Verdict): Tone {
  return verdict === "ON_TIME" || verdict === "ON_TRACK" ? "good" : verdict === "LATE" || verdict === "DELAYED" ? "bad" : verdict === "AT_RISK" ? "warn" : "neutral";
}

export function gpsLinkTone(link: GpsLink): Tone {
  return link === "LINKED" ? "good" : link === "NO_DEVICE" ? "bad" : "warn";
}

export function fleetTone(state: FleetState): Tone {
  return state === "MOVING" ? "good" : state === "STANDING" ? "accent" : state === "OFFLINE" ? "warn" : "bad";
}

/** "1 h 25 min", "3 d 4 h" from minutes. */
export function minutes(value: number | null | undefined): string {
  if (value === null || value === undefined) return "–";
  const m = Math.round(Math.abs(value));
  const sign = value < 0 ? "−" : "";
  if (m < 60) return `${sign}${m} min`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${sign}${h} h${m % 60 ? ` ${m % 60} min` : ""}`;
  return `${sign}${Math.floor(h / 24)} d ${h % 24} h`;
}

export function percent(value: number | null | undefined): string {
  return value === null || value === undefined ? "–" : `${value.toFixed(1)} %`;
}

/** A path with the GPS provider's company filters (?transporter=, ?consigner=) when there are any. */
export function scoped(path: string, scope: { transporter?: string; consigner?: string }): string {
  const params = new URLSearchParams();
  if (scope.transporter) params.set("transporter", scope.transporter);
  if (scope.consigner) params.set("consigner", scope.consigner);
  const query = params.toString();
  if (!query) return path;
  return `${path}${path.includes("?") ? "&" : "?"}${query}`;
}

/** Sends a file (or pasted text) as the raw body; the service reads Excel, CSV and pasted TMS rows. */
export async function upload<T>(path: string, content: Blob | string, fileName: string): Promise<T> {
  const response = await fetch(`${path}${path.includes("?") ? "&" : "?"}fileName=${encodeURIComponent(fileName)}`, {
    method: "POST",
    cache: "no-store",
    headers: { "content-type": typeof content === "string" ? "text/plain; charset=utf-8" : "application/octet-stream" },
    body: content,
  });
  if (response.status === 401) {
    window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`);
  }
  const body = (await response.json().catch(() => ({}))) as T & { error?: string };
  if (!response.ok) throw new Error(body.error ?? `The service answered ${response.status}`);
  return body;
}
