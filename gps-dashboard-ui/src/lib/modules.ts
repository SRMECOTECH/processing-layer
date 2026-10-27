import type { LucideIcon } from "lucide-react";
import { Activity, Car, Database, FileCheck, Gauge, Grid3x3, Layers, Power, Route, ShieldCheck, Table2, TriangleAlert, Waypoints } from "lucide-react";
import type { Feed } from "./nav";

/**
 * The building blocks after the Kafka output topic, in one place: the pipeline diagram, the module pages'
 * headers and "how it works" cards and the developer registry all read from here. Proposed names (consumer
 * groups, Redis keys) of modules that are not built yet are marked "planned".
 */
export type PipelineModule = {
  id: string;
  name: string;
  summary: string;
  href: string;
  icon: LucideIcon;
  status: Feed;
  /** PipelineStage names whose live load is shown for this module (empty while planned). */
  stages: string[];
  consumerGroups: string[];
  reads: string;
  state: string[];
  config: string;
  emits: string[];
  logic: string[];
};

export const OUTPUT_TOPIC = "gps.processed-positions";
export const EVENTS_TOPIC = "gps.vehicle-events";

/** The parallel processors: each is its own consumer group on the output topic, so each sees every reading. */
export const PROCESSORS: PipelineModule[] = [
  {
    id: "speed",
    name: "Speed violation",
    summary: "Sustained over-speed against each entity's own rule, with the speedometer cross-checked against distance over time.",
    href: "/processing/speed",
    icon: Gauge,
    status: "live",
    stages: ["SPEED_VIOLATION"],
    consumerGroups: ["gps-speed-violation-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:speed:{imei}"],
    config: "Redis · gps:config:speed-rule:{scope}",
    emits: ["OVERSPEED", "SPEED_SENSOR_MISMATCH"],
    logic: [
      "Find the rule for the reading: vehicle:{registration}, then tenant:{company}, then default. Rules live in Redis and can differ per entity.",
      "Keep the last few pings of the vehicle in state; a ping counts when its speedometer value is over the limit.",
      "Work out speed from latitude and longitude over the last 2–3 pings (haversine distance ÷ elapsed time) and compare it with the speedometer.",
      "Confirm a violation only when the vehicle stays over the limit continuously for the rule's duration (default 1 minute) and minimum pings.",
      "End the episode when speed drops below the limit minus the hysteresis; repeated disagreement between the two speeds raises a sensor mismatch.",
    ],
  },
  {
    id: "state",
    name: "Vehicle state",
    summary: "Each vehicle's latest state and its historical context, rebuilt from the stream and kept in Redis.",
    href: "/processing/state",
    icon: Car,
    status: "live",
    stages: ["VEHICLE_STATE", "LIVE_LOCATION"],
    consumerGroups: ["gps-vehicle-state-service", "gps-live-location-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:vehicle:{imei}", "gps:history:{imei}", "gps:live:vehicle:{imei}", "gps:live:positions"],
    config: "application.yml · gps.stream.history-length",
    emits: ["State read by every other processor"],
    logic: [
      "Readings are keyed by IMEI, so one vehicle's readings arrive in order on a single partition.",
      "Update the state hash: position, heading, acceleration, odometer, time moving and standing, the Kalman filter.",
      "Append the reading to a capped Redis stream: the vehicle's historical context (last 1,000 readings).",
      "Other processors look things up here (last pings, last known position) instead of keeping their own copy.",
      "Alternative store for history: a compacted Kafka topic keyed by IMEI, replayed into Redis after a restart.",
    ],
  },
  {
    id: "stoppage",
    name: "Stoppage & ignition",
    summary: "Live stoppages from time and distance travelled. Engine on: rules, then traffic and weather agents. Engine off: neutralised.",
    href: "/processing/stoppage",
    icon: Power,
    status: "live",
    stages: ["STOPPAGE_DETECTION", "IGNITION"],
    consumerGroups: ["gps-stoppage-service", "gps-ignition-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:stoppage:{imei}", "gps:state:ignition:{imei}", "gps:context:weather:{cell}"],
    config: "application.yml · gps.stream.stoppage, gps.stream.ignition",
    emits: ["STOPPAGE", "PARKED", "IDLING", "IGNITION_ON", "IGNITION_OFF"],
    logic: [
      "Work out real-time speed from the distance travelled and the time between pings, not only from the speedometer.",
      "Ignition on: standing means under the threshold (2–3 km/h) and within 100 m for at least 3 minutes. That is a stoppage.",
      "Ask the agents why: traffic (fleet vehicles with the engine on within 1 km) and weather (Open-Meteo for the area).",
      "Ignition off: the vehicle is parked. Its points are neutralised and no stoppage is raised.",
      "Ignition comes from the device wire when present, otherwise it is worked out from movement (virtual ignition).",
    ],
  },
  {
    id: "geofence",
    name: "Nested geofencing",
    summary: "Zones inside zones, with the distance from every vehicle to the boundary of each level.",
    href: "/processing/geofence",
    icon: Grid3x3,
    status: "planned",
    stages: [],
    consumerGroups: ["gps-geofence-service (planned)"],
    reads: OUTPUT_TOPIC,
    state: ["gps:live:positions (geo set)", "gps:state:geofence:{imei} (planned)"],
    config: "Zone tree · PostgreSQL + PostGIS (planned)",
    emits: ["GEOFENCE_ENTER", "GEOFENCE_EXIT", "GEOFENCE_DWELL"],
    logic: [
      "Load the zone tree: a parent zone contains its children (region → site → yard → dock).",
      "For each reading, walk the tree from the root to find the deepest zone containing the point.",
      "Measure the signed distance to the boundary of every level (negative inside, positive outside).",
      "Raise entry and exit when the containing zone changes, with a boundary tolerance so a vehicle on the edge does not flap.",
      "Track dwell time per zone and per level.",
    ],
  },
  {
    id: "waypoints",
    name: "Waypoints",
    summary: "Checkpoints along each planned route: arrival, departure, order and ETA.",
    href: "/processing/waypoints",
    icon: Waypoints,
    status: "planned",
    stages: [],
    consumerGroups: ["gps-waypoint-service (planned)"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:waypoint:{imei} (planned)"],
    config: "Route plans · PostgreSQL (planned)",
    emits: ["WAYPOINT_ARRIVED", "WAYPOINT_DEPARTED", "WAYPOINT_MISSED"],
    logic: [
      "Attach the planned waypoints to a trip when it starts (looked up from the trip master).",
      "Mark a waypoint reached when the vehicle enters its radius; record arrival and departure times.",
      "Flag waypoints that were skipped or visited out of order.",
      "Recompute the ETA to the next waypoint from the Kalman-smoothed speed.",
    ],
  },
  {
    id: "distance",
    name: "Cumulative distance",
    summary: "Trip distance incremented on every reading and smoothed with a Kalman filter, so GPS jitter does not add up.",
    href: "/processing/distance",
    icon: Activity,
    status: "live",
    stages: ["VEHICLE_STATE", "TRIP_DETECTION"],
    consumerGroups: ["gps-vehicle-state-service", "gps-trip-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:vehicle:{imei} · kalmanDistanceMeters", "gps_trip.distance_meters"],
    config: "application.yml · gps.stream.vehicle-state, gps.distance",
    emits: ["Distance per trip"],
    logic: [
      "Project each fix to local metres east and north and run a constant-velocity Kalman filter (position and velocity).",
      "Gate outliers with the Mahalanobis distance (99.9 %); reset the filter after 3 outliers in a row.",
      "Add the filtered step to the trip only while the vehicle is moving, so parking jitter adds nothing.",
      "Keep the raw Traccar distance next to it (odometer first, otherwise haversine, jitter clamped, jumps ignored).",
    ],
  },
  // Kafka Streams: the analytics app is one more processor on the output topic (the normalizer sits before it)
  {
    id: "streams",
    name: "Kafka Streams analytics",
    summary: "Latest state of every vehicle as a KTable and 5-minute event-time windows, read live through Interactive Queries.",
    href: "/processing/streams",
    icon: Layers,
    status: "live",
    stages: ["STREAMS_ANALYTICS"],
    consumerGroups: ["gps-streams-analytics"],
    reads: OUTPUT_TOPIC,
    state: ["vehicle-table-store", "activity-window-store", "parity-tally-store (shadow)"],
    config: "application.yml · gps.streams",
    emits: ["gps.vehicle-activity"],
    logic: [
      "Read every processed position with its event time: the moment the device took the reading, not when it arrived.",
      "Fold it into the vehicle's row of the KTable; a late reading is counted but never replaces a newer position.",
      "Add it to the vehicle's 5-minute window; a reading up to 2 minutes late still counts in its own window.",
      "When stream time passes a window's end plus grace, publish its final numbers once to gps.vehicle-activity.",
      "Answer queries from the local RocksDB stores; fleet totals are summed over every instance's share.",
    ],
  },
];

/** Business logic fed by the processors' state and events. */
export const BUSINESS_RULES: PipelineModule[] = [
  {
    id: "overspeed",
    name: "Over-speed alert",
    summary: "GPS device logs checked against the distance and time actually travelled before anyone is alerted.",
    href: "/alerts/overspeed",
    icon: ShieldCheck,
    status: "live",
    stages: ["SPEED_VIOLATION"],
    consumerGroups: ["gps-speed-violation-service"],
    reads: EVENTS_TOPIC,
    state: ["vehicle_event"],
    config: "Speed rules in Redis; alert policy (planned)",
    emits: ["Alert"],
    logic: [],
  },
  {
    id: "trips",
    name: "Trip detection",
    summary: "Trips opened and closed from motion, with the vehicle and company looked up in the master tables.",
    href: "/alerts/trips",
    icon: Route,
    status: "live",
    stages: ["TRIP_DETECTION"],
    consumerGroups: ["gps-trip-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps_trip", "gps_stop", "vehicle_motion_state"],
    config: "application.yml · gps.trip-detection",
    emits: ["Trip opened", "Trip closed"],
    logic: [],
  },
  {
    id: "route",
    name: "Route deviation",
    summary: "Leaving the planned corridor for longer than the tolerance becomes an alert.",
    href: "/alerts/route-deviation",
    icon: TriangleAlert,
    status: "planned",
    stages: [],
    consumerGroups: ["gps-route-deviation-service (planned)"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:route:{imei} (planned)"],
    config: "Corridors · PostgreSQL + PostGIS (planned)",
    emits: ["ROUTE_DEVIATION"],
    logic: [],
  },
];

/** Ingestion control and the two stores. */
export const SINKS: PipelineModule[] = [
  {
    id: "schema",
    name: "Schema validation",
    summary: "Ingestion control: every record is checked against the contract before any write.",
    href: "/storage/schema",
    icon: FileCheck,
    status: "partial",
    stages: ["RAW_FRAME_PROCESSING"],
    consumerGroups: ["gps-raw-frame-processing-service"],
    reads: "gps.raw-device-frames",
    state: [],
    config: "GpsFrameValidationRules",
    emits: ["gps.rejected-frames"],
    logic: [],
  },
  {
    id: "postgres",
    name: "PostgreSQL",
    summary: "Operational store: positions, trips, stops, events and master data.",
    href: "/storage/postgres",
    icon: Database,
    status: "live",
    stages: ["POSITION_STORAGE"],
    consumerGroups: ["gps-position-storage-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps_position", "gps_trip", "gps_stop", "vehicle_event"],
    config: "spring.datasource",
    emits: [],
    logic: [],
  },
  {
    id: "clickhouse",
    name: "ClickHouse",
    summary: "Analytics store: append-only, columnar, for history and fleet-wide aggregates.",
    href: "/storage/clickhouse",
    icon: Table2,
    status: "planned",
    stages: [],
    consumerGroups: ["gps-clickhouse-sink (planned)"],
    reads: OUTPUT_TOPIC,
    state: ["telematics_raw_pings (planned)"],
    config: "Batch 50,000 rows or 2 s (planned)",
    emits: [],
    logic: [],
  },
];

export function moduleById(id: string): PipelineModule | undefined {
  return [...PROCESSORS, ...BUSINESS_RULES, ...SINKS].find((m) => m.id === id);
}
