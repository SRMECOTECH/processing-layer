import type { IconComponent } from "@/components/icons";
import { Activity, Car, Database, FileCheck, Gauge, Grid3x3, Layers, Power, Route, ShieldCheck, Table2, TriangleAlert, Waypoints } from "@/components/icons";
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
  icon: IconComponent;
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
    name: "Geofencing",
    summary: "Which fences every vehicle is inside, live: entries, exits, drive-throughs and over-long stays, against the place master.",
    href: "/processing/geofence",
    icon: Grid3x3,
    status: "live",
    stages: ["GEOFENCE"],
    consumerGroups: ["gps-geofence-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:geofence:{imei}", "geofence_visit", "place"],
    config: "place table (87 smart_truck fences) · gps.modules.geofence.*",
    emits: ["GEOFENCE_ENTER", "GEOFENCE_EXIT", "GEOFENCE_PASS", "GEOFENCE_DWELL"],
    logic: [
      "Index once: India in 0.05° grid cells, every fence filed in every cell its bounding box touches (rebuilt within 30 s of an edit).",
      "Per reading: its cell is two divisions; the cell's short list (usually empty) is tested exactly - flat-earth distance for circles, ray casting for polygons.",
      "Compare with the fences the vehicle was inside: new ones are pending entries, missing ones pending exits.",
      "A side of the border is believed once the truck stayed on it 15 minutes, or at once for an exit beyond 2 radii (the smart_truck rules): no flapping at a gate.",
      "Stamps are real readings: entry = first reading inside, exit = last reading inside, with the gap to the next reading as the uncertainty.",
      "In and out before 15 minutes while moving is a pass-through; a fence jumped between two readings is an inferred pass.",
      "Inside longer than the fence's (or its role's) dwell limit raises GEOFENCE_DWELL, closed when the truck leaves.",
      "Visits are written to geofence_visit (one row per visit, upserted), state to Redis only when a fence changes.",
    ],
  },
  {
    id: "waypoints",
    name: "Waypoints",
    summary: "The planned stops of every trip: reached, left (with the stay), driven through or missed, and in which order.",
    href: "/processing/waypoints",
    icon: Waypoints,
    status: "live",
    stages: ["WAYPOINTS"],
    consumerGroups: ["gps-waypoint-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:waypoints:{imei}", "route_stop", "route_plan"],
    config: "route_stop (planned + geofences on the line) · gps.modules.waypoints.*",
    emits: ["WAYPOINT_ARRIVED", "WAYPOINT_DEPARTED", "WAYPOINT_MISSED"],
    logic: [
      "Stops come with the trip's route plan: waypoints someone planned (MANUAL) and every geofence the planned road crosses (FENCE).",
      "Each stop sits at a distance along the planned line (chainage), so its order and its ETA are known.",
      "Inside a stop for 2 minutes is an arrival, stamped at the first reading inside; leaving is the departure, stamped at the last reading inside, with the stay.",
      "In and out sooner is a drive-through (kept on the stop, no event).",
      "A stop never entered is missed once the truck is 2 km past it along the plan, or reaches the destination; only a missed planned stop raises WAYPOINT_MISSED.",
      "An arrival while an earlier planned stop is still ahead is marked out of order.",
      "Every stop's ETA uses the trip's live pace: planned seconds from here to the stop × pace + the planned stays before it.",
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
  {
    id: "shipments",
    name: "Shipment tracking",
    summary: "Every uploaded shipment followed with its truck's GPS: departure from the plant fence, arrival at the destination fence, delay against the ETA.",
    href: "/tms/shipments",
    icon: Route,
    status: "live",
    stages: ["SHIPMENT_TRACKING"],
    consumerGroups: ["gps-shipment-tracking-service"],
    reads: OUTPUT_TOPIC,
    state: ["shipment (PostgreSQL): left_origin_at, arrived_at, last_*"],
    config: "Places & geofences (place) · gps.modules.shipment-tracking.enabled",
    emits: ["SHIPMENT_DEPARTED", "SHIPMENT_ARRIVED", "SHIPMENT_DELAYED"],
    logic: [
      "Find the truck's shipments the reading can still tell something about: past the gate-out, not closed or arrived before the reading, at most a week old.",
      "Departure = the first reading outside the origin (plant) fence; on an intra-fence trip (destination inside the plant fence) the first moving reading.",
      "Arrival = the first reading inside the destination fence after one outside it, so a trip that starts inside its destination fence is not arrived at once.",
      "Every moment kept is the earliest reading proving it: a redelivered, late (device memory) or replayed reading can only confirm it or move it earlier.",
      "The first reading after the ETA without an arrival opens SHIPMENT_DELAYED; the arrival closes it.",
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
    config: "Speed rules and alert policy in Redis; geofence speed limits (place.speed_limit_kmph)",
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
    name: "Route deviation & ETA",
    summary: "Each trip against its planned road route: off route, alternate roads, wrong direction, live ETA, destination reached.",
    href: "/alerts/route-deviation",
    icon: TriangleAlert,
    status: "live",
    stages: ["ROUTE_DEVIATION"],
    consumerGroups: ["gps-route-deviation-service"],
    reads: OUTPUT_TOPIC,
    state: ["gps:state:route-deviation:{imei}", "route_plan", "route_stop"],
    config: "Valhalla on the HP (truck costing), GraphHopper fallback · gps.modules.route-deviation.*",
    emits: ["ROUTE_DEVIATION", "ROUTE_ALTERNATE", "WRONG_DIRECTION", "ETA_CHANGED", "DESTINATION_REACHED"],
    logic: [
      "Plan once per trip: Valhalla (bidirectional A* on its tiled all-India road graph, truck costing) from the origin geofence to the destination; GraphHopper on this laptop if the HP does not answer.",
      "Per reading: project onto the plan in microseconds (segment grid of 0.01° cells): distance off the line and progress along it (chainage).",
      "Corridor 150 m on highways, 80 m in towns. Outside it is only a suspicion; back inside within 2 readings was drift or a parallel lane.",
      "3 readings AND 5 minutes AND 1 km driven off the line (or 2 km away at once): ask Valhalla for the way from here, off the Kafka thread.",
      "That way within 10 % (or 15 min) of the planned remaining time is an alternate road: ROUTE_ALTERNATE, it becomes the plan. Slower is ROUTE_DEVIATION (HIGH > 30 min added, CRITICAL > 90).",
      "Back inside for 3 readings closes the deviation with minutes off, km off and extra km.",
      "Wrong direction: progress going back 1 km over 3 moving readings, or heading against the road; off the line, the re-plan getting 2 km longer.",
      "ETA = planned seconds ahead × pace + planned stays + driver rests (30 min per 4.5 h). Pace = the truck's own (moving time ÷ planned time, 60 min average) weighed against the lane's by driving time: after 1 h they count equally.",
      "The shown ETA glides at most 2 min per minute and jumps only when a deviation opens or closes; ETA_CHANGED after 30 min or 10 % of the time left.",
      "Inside the destination area: DESTINATION_REACHED with the promised ETA against the actual arrival.",
    ],
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
