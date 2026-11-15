/** Road speed limits and the day-end road report (GET /api/roads/...). */

export type LimitSource = "MANUAL" | "OSM" | "TABLE" | "COMPANY" | "ZONE";

export type RoadDayRow = {
  imei: string;
  registration: string | null;
  label: string;
  ref: string | null;
  roadClass: string;
  area: string;
  stateCode: string | null;
  limitKmph: number;
  limitSource: LimitSource;
  readings: number;
  km: number;
  movingMin: number;
  maxSpeedKmph: number;
  avgMovingKmph: number;
  overReadings: number;
  overKm: number;
  overMin: number;
  maxExcessKmph: number;
};

export type RoadDayVehicle = {
  imei: string;
  registration: string | null;
  km: number;
  movingMin: number;
  overKm: number;
  overMin: number;
  maxSpeedKmph: number;
  roads: number;
  events: number;
  overKmPer100Km: number | null;
  eventsPer100Km: number | null;
};

export type RoadEvent = {
  imei: string;
  registration: string | null;
  severity: string;
  status: string;
  startedAt: string;
  endedAt: string | null;
  latitude: number;
  longitude: number;
  band: string | null;
  limitKmph: number | null;
  excessKmph: number | null;
  maxSpeedKmph: number | null;
  averageSpeedKmph: number | null;
  durationSeconds: number | null;
  verdict: string | null;
  roadLabel: string | null;
  roadClass: string | null;
  roadArea: string | null;
  limitSource: LimitSource | null;
  zoneName: string | null;
  night: boolean;
};

export type RoadDayReport = {
  day: string;
  zone: string;
  vehicles: RoadDayVehicle[];
  roads: RoadDayRow[];
  roadClasses: { roadClass: string; km: number; overKm: number; movingMin: number }[];
  events: RoadEvent[];
  hotspots: { road: string; events: number; vehicles: number; worstExcessKmph: number }[];
  nightEvents: number;
  nightHours: string;
  dataQuality: { readings: number; onKnownRoad: number; onKnownRoadPercent: number | null };
};

export type LearningRun = {
  running: boolean;
  what: string;
  startedAt: string | null;
  finishedAt: string | null;
  pointsSent: number;
  pieces: number;
  failedPieces: number;
  roadsMatched: number;
  roadsAdded: number;
  unmatchedPoints: number;
  valhallaMillis: number;
  message: string;
};

export type RoadStatus = {
  valhallaUrl: string;
  liveMatching: boolean;
  busy: boolean;
  lastRun: LearningRun;
  lastLiveRun: LearningRun | null;
  roads: number | null;
  kilometres: number | null;
};

export type LimitRow = {
  roadClass: string;
  area: "TOWN" | "OPEN";
  stateCode: string;
  limitKmph: number;
  multiLaneLimitKmph: number | null;
};

export type KnownRoad = {
  label: string;
  ref: string | null;
  name: string | null;
  roadClass: string;
  stateCode: string | null;
  area: string;
  limitKmph: number | null;
  limitSource: LimitSource | null;
  pieces: number;
  km: number;
};

export const ROAD_CLASS_LABEL: Record<string, string> = {
  motorway: "Expressway",
  trunk: "National highway (trunk)",
  primary: "Primary (state highway)",
  secondary: "Secondary",
  tertiary: "Tertiary",
  unclassified: "Minor road",
  residential: "Residential",
  service_other: "Service / yard",
};

export const SOURCE_LABEL: Record<LimitSource, string> = {
  MANUAL: "set by hand",
  OSM: "OpenStreetMap",
  TABLE: "road table",
  COMPANY: "company limit",
  ZONE: "geofence",
};

export function classLabel(roadClass: string | null | undefined): string {
  if (!roadClass) return "not on a known road";
  return ROAD_CLASS_LABEL[roadClass] ?? roadClass;
}

export function mapLink(latitude: number, longitude: number): string {
  return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=16/${latitude}/${longitude}`;
}
