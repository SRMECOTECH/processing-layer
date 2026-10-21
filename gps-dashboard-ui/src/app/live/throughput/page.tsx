"use client";

import { ChartLine, Layers } from "lucide-react";
import { AreaChart } from "@/components/ops/charts";
import { Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, ingress, isKafkaFlow, producer, stageName, storage } from "@/lib/pipeline";
import type { StageView } from "@/lib/types";

export default function ThroughputPage() {
  const { data: o } = useOverview();
  const framesIn = ingress(o);
  const stored = storage(o);
  const processed = isKafkaFlow(o) ? producer(o, STAGE.processedPositionProducer) : o?.directFlow;
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const lastMinute = framesIn?.lastMinutePerSecond.reduce((a, b) => a + b, 0);
  const stages: StageView[] = o ? (isKafkaFlow(o) ? [...o.producers, ...o.consumers.map((c) => c.load)] : [o.directFlow]) : [];

  return (
    <div className="stack">
      <PageHeader
        title="Throughput"
        description="How many messages move through each stage. Every producer and consumer counts what it handles; the chart shows the last minute, averaged over 6 seconds."
      />

      <Stats>
        <Tile label="Frames in" value={framesIn?.messagesPerSecond} format={formatRate} unit="msg/s" big foot={`${formatNumber(lastMinute)} in the last minute`} />
        <Tile label="Processed out" value={processed?.messagesPerSecond} format={formatRate} unit="msg/s" big foot={`${formatNumber(processed?.totalMessages)} since start`} />
        <Tile label="Stored" value={stored?.messagesPerSecond} format={formatRate} unit="rows/s" big foot={`${formatNumber(stored?.failures ?? 0)} failed writes`} />
        <Tile label="Rejected" value={rejected?.totalMessages ?? (o ? 0 : undefined)} tone={rejected && rejected.totalMessages > 0 ? "warn" : undefined} big foot="failed validation since start" />
      </Stats>

      <Card icon={ChartLine} title="Frames in against stored" subtitle="Per second, last minute." live>
        <AreaChart
          series={[
            { name: "Frames in", values: framesIn?.lastMinutePerSecond ?? [], strong: true },
            { name: "Stored", values: stored?.lastMinutePerSecond ?? [] },
          ]}
        />
      </Card>

      <Card icon={Layers} title="Every stage" subtitle="Producers write to a topic; consumers read one." live flush>
        {stages.length === 0 ? (
          <Empty>Waiting for the service.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Stage</th>
                  <th>Kind</th>
                  <th>Topic</th>
                  <th className="right">msg/s</th>
                  <th className="right">Total</th>
                  <th className="right">Failures</th>
                  <th className="right">Devices</th>
                  <th>Last message</th>
                </tr>
              </thead>
              <tbody>
                {stages.map((s) => (
                  <tr key={s.stage}>
                    <td>{stageName(s.stage)}</td>
                    <td>
                      <Tag tone={s.consumerGroup ? "accent" : "neutral"}>{s.consumerGroup ? "CONSUMER" : s.topic ? "PRODUCER" : "DIRECT"}</Tag>
                    </td>
                    <td className="mono muted">{s.topic ?? "–"}</td>
                    <td className="right mono">{formatRate(s.messagesPerSecond)}</td>
                    <td className="right mono">{formatNumber(s.totalMessages)}</td>
                    <td className={`right mono${s.failures ? " bad" : ""}`}>{formatNumber(s.failures)}</td>
                    <td className="right mono">{formatNumber(s.devicesSeen)}</td>
                    <td className="muted">{formatAgo(s.lastMessageAt)}</td>
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
