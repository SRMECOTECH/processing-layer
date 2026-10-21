import { formatNumber } from "./format";
import { STAGE, isHealthyGroupState, isKafkaFlow, producer, stageName } from "./pipeline";
import type { EngineOverview } from "./types";

export type Alert = { tone: "bad" | "warn" | "info"; title: string; text: string; href: string };

const LAG_WORTH_MENTIONING = 1000;

/** Real alerts worked out from the engine overview: what an operator should look at right now. */
export function alertsFrom(overview: EngineOverview | undefined): Alert[] {
  if (!overview) return [];
  const alerts: Alert[] = [];

  if (isKafkaFlow(overview) && overview.kafkaProblem) {
    alerts.push({ tone: "bad", title: "Kafka is not answering", text: overview.kafkaProblem, href: "/developer/processors" });
  }
  if (overview.redisProblem) {
    alerts.push({ tone: "bad", title: "Redis is not answering", text: overview.redisProblem, href: "/processing/state" });
  }
  if (overview.storedPositions < 0) {
    alerts.push({ tone: "bad", title: "PostgreSQL is not answering", text: "Positions cannot be counted or stored.", href: "/storage/postgres" });
  }
  for (const consumer of overview.consumers) {
    if (isKafkaFlow(overview) && !isHealthyGroupState(consumer.groupState)) {
      alerts.push({
        tone: "warn",
        title: `${stageName(consumer.load.stage)} group is ${consumer.groupState.toLowerCase()}`,
        text: `Consumer group ${consumer.load.consumerGroup}`,
        href: "/analytics/latency",
      });
    }
    if (consumer.totalLag > LAG_WORTH_MENTIONING) {
      alerts.push({
        tone: "warn",
        title: `${stageName(consumer.load.stage)} is behind`,
        text: `${formatNumber(consumer.totalLag)} messages still to read`,
        href: "/analytics/latency",
      });
    }
    if (consumer.load.failures > 0) {
      alerts.push({
        tone: "bad",
        title: `${stageName(consumer.load.stage)} has failures`,
        text: `${formatNumber(consumer.load.failures)} messages failed`,
        href: "/developer/processors",
      });
    }
  }
  const rejected = producer(overview, STAGE.rejectedFrameProducer);
  if (rejected && rejected.totalMessages > 0) {
    alerts.push({
      tone: "info",
      title: "Frames rejected at the gate",
      text: `${formatNumber(rejected.totalMessages)} frames failed validation`,
      href: "/storage/rejected",
    });
  }
  if (!overview.simulator.running) {
    alerts.push({ tone: "info", title: "Simulator is stopped", text: "No new frames are arriving from the simulated fleet.", href: "/live/simulator" });
  }
  return alerts;
}

export function seriousCount(alerts: Alert[]): number {
  return alerts.filter((a) => a.tone !== "info").length;
}
