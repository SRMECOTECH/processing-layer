// JSON shapes returned by gps-streaming-service. They mirror the Java records:
//   EngineViews, KafkaTopologyInspector, SimulationStatus, GpsPositionView, TripView, StopView.
// Times are ISO-8601 strings (java.time.Instant).

export type SimulationStatus = {
  processingFlow: string;
  running: boolean;
  vehicleCount: number;
  framesSent: number;
  round: number;
  roundsPerDrive: number;
  driveNumber: number;
  replayAsLive: boolean;
  lastMessage: string | null;
};

export type StageView = {
  stage: string;
  label: string;
  topic: string | null;
  consumerGroup: string | null;
  totalMessages: number;
  failures: number;
  messagesPerSecond: number;
  /** One count per second, oldest first, the current second excluded. */
  lastMinutePerSecond: number[];
  messagesByPartition: Record<string, number>;
  devicesSeen: number;
  lastMessageAt: string | null;
};

export type GroupMember = {
  clientId: string;
  host: string;
  assignedPartitions: string[];
};

export type PartitionLag = {
  topic: string;
  partition: number;
  committedOffset: number;
  latestOffset: number;
  lag: number;
  ownerClientId: string | null;
};

export type ConsumerView = {
  load: StageView;
  groupState: string;
  members: GroupMember[];
  partitions: PartitionLag[];
  totalLag: number;
};

export type PartitionOffsets = {
  partition: number;
  earliestOffset: number;
  latestOffset: number;
};

export type TopicView = {
  name: string;
  partitions: PartitionOffsets[];
  messagesKept: number;
  messagesEverWritten: number;
};

export type EngineOverview = {
  processingFlow: string;
  generatedAt: string;
  simulator: SimulationStatus;
  producers: StageView[];
  consumers: ConsumerView[];
  directFlow: StageView;
  topics: TopicView[];
  kafkaProblem: string | null;
  /** -1 when PostgreSQL did not answer. */
  storedPositions: number;
  vehiclesWithLivePosition: number;
  redisProblem: string | null;
};

export type DeviceLoadView = {
  imei: string;
  partition: number;
  messagesByStage: Record<string, number>;
  /** Fields of the Redis hash gps:live:vehicle:{imei}; empty in the device list. */
  livePosition: Record<string, string>;
};

export type GpsPositionView = {
  imei: string;
  tenantCode: string | null;
  vehicleRegistrationNo: string | null;
  recordedAt: string;
  latitude: number;
  longitude: number;
  speedKmph: number;
  movementStatus: "MOVING" | "STOPPED";
  distanceFromPreviousMeters: number;
  secondsSincePreviousReading: number;
  storedAt: string;
};

export type TripView = {
  imei: string;
  tenantCode: string | null;
  vehicleRegistrationNo: string | null;
  status: "OPEN" | "CLOSED";
  startRecordedAt: string;
  endRecordedAt: string | null;
  startLatitude: number;
  startLongitude: number;
  endLatitude: number;
  endLongitude: number;
  distanceMeters: number;
  durationSeconds: number;
  maxSpeedKmph: number;
  averageSpeedKmph: number;
  readingCount: number;
  lateReadingCount: number;
};

export type StopView = {
  imei: string;
  tenantCode: string | null;
  vehicleRegistrationNo: string | null;
  status: "OPEN" | "CLOSED";
  startRecordedAt: string;
  endRecordedAt: string | null;
  latitude: number;
  longitude: number;
  durationSeconds: number;
  readingCount: number;
};

export type TripCounts = {
  openTrips: number;
  closedTrips: number;
  openStops: number;
  closedStops: number;
};

// ---- stream consumers (speed violation, vehicle state, stoppage, ignition)

export type VehicleEventView = {
  id: number;
  imei: string;
  vehicleRegistrationNo: string | null;
  tenantCode: string | null;
  type:
    | "OVERSPEED"
    | "SPEED_SENSOR_MISMATCH"
    | "STOPPAGE"
    | "PARKED"
    | "IDLING"
    | "IGNITION_ON"
    | "IGNITION_OFF"
    | "HARSH_ACCELERATION"
    | "HARSH_BRAKING";
  severity: "INFO" | "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  status: "OPEN" | "CLOSED";
  startedAt: string;
  endedAt: string | null;
  latitude: number;
  longitude: number;
  detectedBy: string;
  details: Record<string, unknown>;
  updatedAt: string;
};

export type EventTypeCount = { type: string; last24Hours: number; openNow: number };

export type SpeedRule = {
  scope: string;
  limitKmph: number;
  minDurationSeconds: number;
  minConsecutivePings: number;
  hysteresisKmph: number;
  toleranceKmph: number;
  tolerancePercent: number;
  maxGapSeconds: number;
  windowPings: number;
  requireGpsAgreement: boolean;
  mismatchPings: number;
};

export type ActiveSpeedEpisode = {
  imei: string;
  phase: "CANDIDATE" | "VIOLATING";
  episodeStartedAt: string;
  maxSpeedKmph: number;
  averageSpeedKmph: number;
  overLimitReadings: number;
  lastGpsSpeedKmph: number | null;
  lastVerdict: string;
  ruleScope: string;
  limitKmph: number;
};

export type CacheEntry = {
  key: string;
  type: string;
  purpose: string;
  value: Record<string, string | number>;
};

export type VehicleTrackingState = {
  imei: string;
  cache: CacheEntry[];
  history: Record<string, string>[];
  events: Record<string, unknown>[];
  processing: { recordedAt: string; latitude: number; longitude: number; totalDistanceMeters: number; odometerMeters: number | null } | null;
  tripMotion: Record<string, unknown> | null;
};

export type ResetReport = {
  at: string;
  tookMillis: number;
  fleetWasRunning: boolean;
  fleetRestarted: boolean;
  listenersSkippedToEnd: number;
  kafkaRecordsDeleted: Record<string, number>;
  tablesEmptied: string[];
  redisKeysDeleted: number;
  notes: string[];
};
