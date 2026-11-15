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

/** Everything stored about one road piece beyond its class and limit (GET /api/roads/pieces/{id}, V13). */
export type RoadPieceDetails = {
  use: string | null;
  traversability: string | null;
  bridge: boolean | null;
  tunnel: boolean | null;
  roundabout: boolean | null;
  toll: boolean | null;
  curvature: number | null;
  valhallaKmph: number | null;
  speedType: string | null;
  truckRoute: boolean | null;
  surface: string | null;
  beginHeading: number | null;
  endHeading: number | null;
  countryCode: string | null;
  wayId: number;
  osmLimitKmph: number | null;
  lengthM: number;
  timesMatched: number;
  firstSeenAt: string;
  /** The whole edge as Valhalla sent it (JSON text), or null for pieces learned before V13. */
  attributes: string | null;
};

/** One road pass: a vehicle on one road from the first reading to the last (GET /api/roads/passes). */
export type RoadPass = {
  id: number;
  imei: string;
  registration: string | null;
  tripId: number | null;
  label: string;
  ref: string | null;
  roadClass: string;
  area: string;
  roadLimitKmph: number | null;
  limitKmph: number;
  limitSource: LimitSource;
  enteredAt: string;
  leftAt: string;
  readings: number;
  pieces: number;
  km: number;
  movingMin: number;
  stoppedMin: number;
  avgKmph: number;
  maxKmph: number;
  expectedKmph: number | null;
  overReadings: number;
  overMin: number;
  maxExcessKmph: number;
  bridges: number;
  maxCurvature: number | null;
  entryLat: number;
  entryLon: number;
  exitLat: number;
  exitLon: number;
};

/** The learned roads' details in totals (GET /api/roads/attributes). */
export type RoadAttributes = {
  totals: {
    pieces: number;
    km: number;
    withDetails: number;
    oneWayKm: number;
    twoWayKm: number;
    bridges: number;
    bridgeKm: number;
    tunnels: number;
    roundabouts: number;
    tollPieces: number;
    truckRoutePieces: number;
    sharpCurves: number;
    osmLimitPieces: number;
    manualLimitPieces: number;
  };
  uses: { use: string; pieces: number; km: number }[];
  curvature: { curvature: number; pieces: number; km: number }[];
  speedByClass: { roadClass: string; pieces: number; km: number; valhallaKmph: number | null; lanes: number }[];
  surfaces: { surface: string; pieces: number }[];
  bridges: { id: number; label: string; roadClass: string; lengthM: number; lat: number; lon: number }[];
  curves: { id: number; label: string; roadClass: string; curvature: number; lengthM: number; lat: number; lon: number }[];
  readings: { readings: number; withRoad: number; avgOffM: number | null; p95OffM: number | null };
};

export function directionLabel(traversability: string | null | undefined): string {
  if (!traversability) return "–";
  return traversability === "both" ? "two-way" : "one-way";
}

export function yesNo(value: boolean | null | undefined): string {
  return value == null ? "–" : value ? "yes" : "no";
}
