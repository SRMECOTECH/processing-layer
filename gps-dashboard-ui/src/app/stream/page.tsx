"use client";

import { Activity, Network, Workflow } from "lucide-react";
import { AreaChart } from "@/components/ops/charts";
import { Card, Empty, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { FleetButton } from "@/components/simulator-control";
import { formatAgo, formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, consumer, ingress, isHealthyGroupState, isKafkaFlow, producer, stageName, storage } from "@/lib/pipeline";

export default function PipelinePage() {
  const { data: o } = useOverview();
  const kafka = isKafkaFlow(o);
  const framesIn = ingress(o);
  const stored = storage(o);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const processing = consumer(o, STAGE.rawFrameProcessing);
  const totalLag = o?.consumers.reduce((sum, c) => sum + c.totalLag, 0) ?? 0;
  const stable = o?.consumers.filter((c) => isHealthyGroupState(c.groupState)).length ?? 0;
  const lastMinute = framesIn?.lastMinutePerSecond.reduce((a, b) => a + b, 0);
  const devicesSeen = framesIn?.devicesSeen ?? 0;

  return (
    <div className="stack">
      <PageTitle
        title="Pipeline overview"
        subtitle="The streaming pipeline after Kafka: every frame is validated, enriched with movement and fanned out to PostgreSQL, Redis and trip detection."
      />

      <Card
        icon={Network}
        title={kafka ? (processing?.load.consumerGroup ?? "gps-raw-frame-processing-service") : "Direct processing flow"}
        badges={
          <Tag tone={o?.simulator.running ? "good" : "neutral"}>
            {o?.simulator.running ? "ACTIVE PIPELINE" : "IDLE"}
          </Tag>
        }
        subtitle={
          <>
            <span className="tag neutral mono">
              {kafka ? `Partitions: ${processing?.partitions.length || 3} · Groups: ${o?.consumers.length ?? 0}` : "No Kafka"}
            </span>
            <span style={{ display: "block", marginTop: 8 }}>
              Validates every frame, works out distance and movement, then hands it to storage, the live map and trip detection.
            </span>
          </>
        }
        live
        actions={
          <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
            <div style={{ textAlign: "right" }}>
              <div className="mono" style={{ fontSize: 26, fontWeight: 500 }}>
                {formatRate(framesIn?.messagesPerSecond)} <span className="tile-unit">msg/sec</span>
              </div>
              <div className="faint mono" style={{ fontSize: 12 }}>
                Lag: {formatNumber(totalLag)} across {o?.consumers.length ?? 0} groups
              </div>
            </div>
            <FleetButton />
          </div>
        }
      >
        <div className="grid-4">
          <Tile
            label="Pings analysed (1 min rolling)"
            icon={Activity}
            value={lastMinute}
            note={framesIn ? `${formatRate(framesIn.messagesPerSecond)}/s` : undefined}
            progress={framesIn && framesIn.messagesPerSecond > 0 ? 1 : 0}
          />
          <Tile
            label="Positions stored"
            value={o && o.storedPositions >= 0 ? o.storedPositions : undefined}
            foot={stored ? `${formatRate(stored.messagesPerSecond)} writes per second` : undefined}
          />
          <Tile
            label="Vehicles on the live map"
            value={o?.vehiclesWithLivePosition}
            unit={devicesSeen ? `/ ${formatNumber(devicesSeen)}` : undefined}
            progress={devicesSeen ? (o?.vehiclesWithLivePosition ?? 0) / devicesSeen : undefined}
          />
          <Tile
            label="Rejected at the gate"
            value={rejected?.totalMessages ?? (kafka ? undefined : 0)}
            tone={rejected && rejected.totalMessages > 0 ? "bad" : undefined}
            foot={`${stable} of ${o?.consumers.length ?? 0} consumer groups stable`}
          />
        </div>
      </Card>

      <Card
        icon={Workflow}
        title="Real-time stream flow"
        subtitle="Frames in against positions stored, per second over the last minute (6-second average)."
        live
      >
        <AreaChart
          series={[
            { name: "Frames in", values: framesIn?.lastMinutePerSecond ?? [], strong: true },
            { name: "Stored", values: stored?.lastMinutePerSecond ?? [] },
          ]}
        />
      </Card>

      <Card icon={Network} title="Consumer groups" subtitle="Each group reads the whole topic for one purpose and keeps its own offset." live>
        {!o ? (
          <Empty>Waiting for the service.</Empty>
        ) : !kafka ? (
          <Empty>The direct flow does not use consumer groups.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Processor</th>
                  <th>Consumer group</th>
                  <th className="right">msg/s</th>
                  <th className="right">Lag</th>
                  <th>Last message</th>
                  <th>State</th>
                </tr>
              </thead>
              <tbody>
                {o.consumers.map((c) => (
                  <tr key={c.load.stage}>
                    <td>{stageName(c.load.stage)}</td>
                    <td className="mono muted">{c.load.consumerGroup}</td>
                    <td className="right mono">{formatRate(c.load.messagesPerSecond)}</td>
                    <td className={`right mono${c.totalLag > 1000 ? " warn" : ""}`}>{formatNumber(c.totalLag)}</td>
                    <td className="muted">{formatAgo(c.load.lastMessageAt)}</td>
                    <td>
                      <Tag tone={isHealthyGroupState(c.groupState) ? "good" : "warn"}>{c.groupState.toUpperCase()}</Tag>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
