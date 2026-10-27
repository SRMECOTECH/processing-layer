// JSON shapes returned by gps-streaming-service. They mirror the Java records:
//   EngineViews, KafkaTopologyInspector, SimulationStatus, GpsPositionView, TripView, StopView, KafkaStreamsViews.
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
  /** Who turns raw frames into processed positions (older services leave it out). */
  normalization?: NormalizationView;
};

export type NormalizationView = {
  engine: "kafka-streams" | "listener" | "direct";
  mode: "owner" | "shadow" | "off" | null;
  normalizerState: string | null;
  analyticsState: string | null;
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

// ---------------------------------------------------------------- Kafka Streams (GET /api/streams/...)

export type StreamsNode = { name: string; kind: "SOURCE" | "PROCESSOR" | "SINK" | "NODE"; topics: string[]; stores: string[]; next: string[] };
export type StreamsSubtopology = { id: number; sourceTopics: string[]; sinkTopics: string[]; stores: string[]; nodes: StreamsNode[] };
export type StreamsInput = { topic: string; partition: number; committedOffset: number | null; endOffset: number | null; lag: number | null };
export type StreamsTask = { id: string; subtopology: number; partition: number; inputs: StreamsInput[] };
export type StreamsThread = { name: string; state: string; activeTasks: StreamsTask[]; standbyTasks: StreamsTask[] };
export type StreamsStoreLag = { store: string; partition: number; position: number; endOffset: number; lag: number };
export type StreamsRestoration = {
  store: string;
  changelog: string;
  partition: number;
  recordsToRestore: number;
  restored: number;
  startedAt: string;
  endedAt: string | null;
  done: boolean;
};
export type StreamsStateChange = { at: string; from: string; to: string };
export type StreamsInstance = { host: string; port: number; local: boolean; activePartitions: string[]; standbyPartitions: string[] };
export type StreamsMetrics = {
  processRate: number;
  processTotal: number;
  commitRate: number;
  commitLatencyAvgMs: number;
  pollRate: number;
  droppedRecordsTotal: number;
  aliveStreamThreads: number;
};

export type StreamsApp = {
  role: "normalizer" | "analytics";
  applicationId: string;
  purpose: string;
  state: string;
  guarantee: string;
  startedAt: string | null;
  replacedThreads: number;
  lastFailure: string | null;
  topology: { subtopologies: StreamsSubtopology[] };
  threads: StreamsThread[];
  storeLags: StreamsStoreLag[];
  storeEntries: Record<string, number | null>;
  restorations: StreamsRestoration[];
  stateChanges: StreamsStateChange[];
  instances: StreamsInstance[];
  metrics: StreamsMetrics | null;
};

export type StreamsSettings = {
  windowSizeSeconds: number;
  windowGraceSeconds: number;
  windowRetentionSeconds: number;
  maxLatenessSeconds: number;
  seenReadingsRetentionSeconds: number;
  parityGraceSeconds: number;
  threads: number;
  standbyReplicas: number;
  replicationFactor: number;
  rocksdbOffHeapMb: number;
  stateDir: string | null;
};

export type StreamsOverview = {
  enabled: boolean;
  reason: string | null;
  normalization: "owner" | "shadow" | "off" | null;
  normalizer: string;
  instance: string | null;
  generatedAt: string;
  settings: StreamsSettings | null;
  apps: StreamsApp[];
};

export type VehicleSnapshot = {
  imei: string;
  recordedAt: string | null;
  latitude: number;
  longitude: number;
  speedKmph: number;
  movementStatus: "MOVING" | "STOPPED" | null;
  statusSince: string | null;
  totalDistanceMeters: number | null;
  distanceSource: string | null;
  ignition: boolean | null;
  readings: number;
  outOfOrderReadings: number;
  maxSpeedKmph: number;
  firstRecordedAt: string | null;
  lastProcessedAt: string | null;
};

export type VehicleActivity = {
  imei: string | null;
  windowStart: string;
  windowEnd: string;
  readings: number;
  movingReadings: number;
  outOfOrderReadings: number;
  maxSpeedKmph: number;
  averageSpeedKmph: number;
  movingShare: number;
  distanceMeters: number;
  firstRecordedAt: string | null;
  lastRecordedAt: string | null;
};

export type StreamsVehicleView = {
  snapshot: VehicleSnapshot | null;
  partition: number;
  servedBy: string;
  standbyHosts: string[];
  windows: VehicleActivity[];
};

export type FleetWindow = {
  windowStart: string;
  windowEnd: string;
  open: boolean;
  vehicles: number;
  readings: number;
  movingReadings: number;
  outOfOrderReadings: number;
  maxSpeedKmph: number;
  averageSpeedKmph: number;
  movingShare: number;
  distanceMeters: number;
};

export type ParityOutcome = "MATCH" | "MISMATCH" | "WARMUP" | "ONLY_LISTENER" | "ONLY_STREAMS";
export type ParityVerdict = { outcome: ParityOutcome; recordedAt: string; differences: string[] };
export type ParityTally = {
  matched: number;
  mismatched: number;
  warmup: number;
  onlyListener: number;
  onlyStreams: number;
  lastRecordedAt: string | null;
  lastDifference: ParityVerdict | null;
};
export type ParityView = {
  active: boolean;
  note: string | null;
  totals: ParityTally;
  streamsAccepted: number;
  streamsRejected: number;
  listenerAccepted: number;
  listenerRejected: number;
  vehicles: { imei: string; tally: ParityTally }[];
};

// ---------------------------------------------------------------- Data safety (GET /api/data-safety, /api/dead-letters, /api/rejected-frames)

export type SafetyStatus = "OK" | "WARN" | "FAIL";
export type SafetyCheck = { id: string; title: string; status: SafetyStatus; detail: string };
export type TopicSafety = {
  topic: string;
  partitions: number;
  replicationFactor: number;
  minInsyncReplicas: number | null;
  retentionMs: number | null;
  cleanupPolicy: string | null;
  recordsKept: number;
  bytesOnDisk: number;
};
export type GroupSafety = { group: string; state: string; members: number; lag: number; lostRecords: number; lostPartitions: Record<string, number> };
export type BrokerSafety = {
  brokers: number;
  flushIntervalMessages: number | null;
  offsetsRetentionMinutes: number | null;
  autoCreateTopics: boolean | null;
  defaultReplicationFactor: string | null;
  minInsyncReplicas: string | null;
  uncleanLeaderElection: string | null;
};
export type JournalStats = {
  segments: number;
  unacknowledged: number;
  bytesOnDisk: number;
  appended: number;
  acknowledged: number;
  acknowledgedThrough: number;
  oldestUnacknowledgedSegmentAt: string | null;
};
export type JournalStatus = {
  catchingUp: boolean;
  inFlight: number;
  failedSends: number;
  lastFailure: string | null;
  lastFailureAt: string | null;
  journal: JournalStats;
  directory: string;
  fsyncEveryRecord: boolean;
};
export type CountBy = { name: string; parked: number; total: number };
export type DeadLetterSummary = {
  parked: number;
  replayed: number;
  discarded: number;
  oldestParkedAt: string | null;
  byTopic: CountBy[];
  byException: CountBy[];
};
export type ReasonCount = { reason: string; lastDay: number; total: number };
export type RejectedSummary = { total: number; lastDay: number; newest: string | null; byReason: ReasonCount[] };
export type DataSafetyReport = {
  verdict: SafetyStatus;
  generatedAt: string;
  processingFlow: string;
  retentionTargetMs: number;
  checks: SafetyCheck[];
  topics: TopicSafety[];
  groups: GroupSafety[];
  broker: BrokerSafety | null;
  journal: JournalStatus | null;
  deadLetters: DeadLetterSummary;
  rejectedFrames: RejectedSummary;
  producer: Record<string, string>;
  problem: string | null;
};
export type DeadLetterRow = {
  id: number;
  source: "listener" | "kafka-streams";
  originalTopic: string | null;
  originalPartition: number | null;
  originalOffset: number | null;
  consumerGroup: string | null;
  recordKey: string | null;
  recordValue: string | null;
  exceptionClass: string | null;
  exceptionMessage: string | null;
  stacktrace: string | null;
  parkedAt: string;
  status: "PARKED" | "REPLAYED" | "DISCARDED";
  replayCount: number;
  lastReplayedAt: string | null;
  resolutionNote: string | null;
};
export type RejectedFrameRow = { id: number; imei: string | null; rejectionReason: string; originalPayload: string; rejectedAt: string };
