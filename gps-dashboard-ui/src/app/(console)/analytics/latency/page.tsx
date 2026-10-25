"use client";

import { Timer, Workflow } from "lucide-react";
import { AreaChart } from "@/components/ops/charts";
import { Card, Empty, PageTitle, Progress, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { isHealthyGroupState, isKafkaFlow, stageName } from "@/lib/pipeline";

export default function LatencyPage() {
  const { data: o } = useOverview();
  const consumers = o?.consumers ?? [];
  const totalLag = consumers.reduce((s, c) => s + c.totalLag, 0);
  const worst = [...consumers].sort((a, b) => b.totalLag - a.totalLag)[0];
  const maxLag = Math.max(1, ...consumers.map((c) => c.totalLag));

  return (
    <div className="stack">
      <PageTitle
        title="Latency and backpressure"
        subtitle="How far each consumer is behind the newest message. Lag that keeps growing means a stage cannot keep up and needs more consumers or a faster sink."
      />

      <div className="grid-3">
        <Tile label="Total lag" value={totalLag} unit="messages" big tone={totalLag > 1000 ? "bad" : undefined} />
        <Tile label="Furthest behind" value={worst ? stageName(worst.load.stage) : "–"} foot={worst ? `${formatNumber(worst.totalLag)} messages` : undefined} />
        <Tile label="Stable groups" value={consumers.filter((c) => isHealthyGroupState(c.groupState)).length} unit={`/ ${consumers.length}`} big />
      </div>

      <Card icon={Timer} title="Lag by consumer group" subtitle="Bar length is relative to the group furthest behind." live>
        {!o ? (
          <Empty>Waiting for the service.</Empty>
        ) : !isKafkaFlow(o) ? (
          <Empty>The direct flow has no consumer lag: every frame is handled in one call.</Empty>
        ) : (
          <div style={{ display: "grid", gap: 12 }}>
            {consumers.map((c) => (
              <div className="inner" key={c.load.stage}>
                <div style={{ display: "flex", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
                  <span>
                    <strong>{stageName(c.load.stage)}</strong>{" "}
                    <span className="mono faint" style={{ fontSize: 12 }}>
                      {c.load.consumerGroup}
                    </span>
                  </span>
                  <span className="mono">
                    {formatNumber(c.totalLag)} behind · {formatRate(c.load.messagesPerSecond)} msg/s ·{" "}
                    <span className="faint">{formatAgo(c.load.lastMessageAt)}</span>
                  </span>
                </div>
                <Progress value={c.totalLag / maxLag} tone={c.totalLag > 1000 ? "warn" : undefined} />
                <div style={{ marginTop: 8 }}>
                  <Tag tone={isHealthyGroupState(c.groupState) ? "good" : "warn"}>{c.groupState.toUpperCase()}</Tag>{" "}
                  <Tag tone="neutral" mono>
                    {c.members.length} consumers
                  </Tag>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card icon={Workflow} title="Throughput per stage" subtitle="Messages per second over the last minute (6-second average)." live>
        <div className="grid-2">
          {consumers.slice(0, 4).map((c) => (
            <div className="inner" key={c.load.stage}>
              <div className="inner-title">{stageName(c.load.stage)}</div>
              <AreaChart series={[{ name: "Read", values: c.load.lastMinutePerSecond, strong: true }]} />
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
