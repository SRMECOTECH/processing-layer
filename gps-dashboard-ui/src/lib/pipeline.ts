import type { ConsumerView, EngineOverview, StageView } from "./types";

// Stage names from com.srm.gps.configuration.PipelineStage.
export const STAGE = {
  rawFrameProducer: "RAW_FRAME_PRODUCER",
  processedPositionProducer: "PROCESSED_POSITION_PRODUCER",
  rejectedFrameProducer: "REJECTED_FRAME_PRODUCER",
  rawFrameProcessing: "RAW_FRAME_PROCESSING",
  positionStorage: "POSITION_STORAGE",
  liveLocation: "LIVE_LOCATION",
  tripDetection: "TRIP_DETECTION",
  rejectedFrameLogger: "REJECTED_FRAME_LOGGER",
  directProcessing: "DIRECT_PROCESSING",
} as const;

/** Short, plain names for the stages; the service's own labels are longer. */
export const STAGE_NAME: Record<string, string> = {
  RAW_FRAME_PRODUCER: "Device frames",
  PROCESSED_POSITION_PRODUCER: "Processed positions",
  REJECTED_FRAME_PRODUCER: "Rejected frames",
  RAW_FRAME_PROCESSING: "Validation and movement",
  POSITION_STORAGE: "PostgreSQL storage",
  LIVE_LOCATION: "Live location in Redis",
  TRIP_DETECTION: "Trip and stop detection",
  REJECTED_FRAME_LOGGER: "Rejected frame log",
  DIRECT_PROCESSING: "Direct flow",
};

export function stageName(stage: string): string {
  return STAGE_NAME[stage] ?? stage;
}

export function producer(overview: EngineOverview | undefined, stage: string): StageView | undefined {
  return overview?.producers.find((p) => p.stage === stage);
}

export function consumer(overview: EngineOverview | undefined, stage: string): ConsumerView | undefined {
  return overview?.consumers.find((c) => c.load.stage === stage);
}

export function isKafkaFlow(overview: EngineOverview | undefined): boolean {
  return overview?.processingFlow === "kafka";
}

/** Frames entering the pipeline: the first producer in the Kafka flow, the direct flow otherwise. */
export function ingress(overview: EngineOverview | undefined): StageView | undefined {
  if (!overview) return undefined;
  return isKafkaFlow(overview) ? producer(overview, STAGE.rawFrameProducer) : overview.directFlow;
}

/** Positions written to PostgreSQL per second. */
export function storage(overview: EngineOverview | undefined): StageView | undefined {
  if (!overview) return undefined;
  return isKafkaFlow(overview) ? consumer(overview, STAGE.positionStorage)?.load : overview.directFlow;
}

/** Consumer group states that mean "working normally". Kafka reports "Stable". */
export function isHealthyGroupState(state: string): boolean {
  return state.toUpperCase() === "STABLE";
}
