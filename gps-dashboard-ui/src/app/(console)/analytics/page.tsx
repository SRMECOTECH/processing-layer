"use client";

import Link from "next/link";
import { Car, ChartLine, Database, Gauge, HardDrive, MoreVertical, Radio, Server, Table2, Timer } from "@/components/icons";
import { AreaChart } from "@/components/ops/charts";
import { Card, KeyValues, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, consumer, ingress, isHealthyGroupState, isKafkaFlow, producer, storage } from "@/lib/pipeline";
import type { TripCounts } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function LiveTelemetryPage() {
  const { data: o } = useOverview();
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const kafka = isKafkaFlow(o);
  const framesIn = ingress(o);
  const stored = storage(o);
  const live = consumer(o, STAGE.liveLocation);
  const processed = producer(o, STAGE.processedPositionProducer);
  const totalLag = o?.consumers.reduce((s, c) => s + c.totalLag, 0) ?? 0;
  const seen = framesIn?.devicesSeen ?? 0;

  return (
    <div className="stack">
      <PageTitle
        title="Live stream telemetry"
        subtitle="End-to-end view of the pipeline: what comes in, how fast it moves through each stage and how many vehicles are connected."
      />

      <div className="grid-4">
        <Tile label="Telemetry ingestion" icon={Radio} value={framesIn?.totalMessages} big foot={`Current: ${formatRate(framesIn?.messagesPerSecond)} msg/sec`} progress={1} />
        <Tile label="Positions stored" icon={Database} value={o && o.storedPositions >= 0 ? o.storedPositions : undefined} big foot="PostgreSQL gps_position" />
        <Tile label="Trips in progress" icon={Gauge} value={counts.data?.openTrips} big foot={`${formatNumber(counts.data?.closedTrips)} completed`} />
        <Tile
          label="Connected units"
          icon={Car}
          value={o?.vehiclesWithLivePosition}
          unit={seen ? `/ ${formatNumber(seen)}` : undefined}
          big
          foot={seen ? `Active heartbeat ${Math.round(((o?.vehiclesWithLivePosition ?? 0) / seen) * 100)}%` : undefined}
          progress={seen ? (o?.vehiclesWithLivePosition ?? 0) / seen : undefined}
        />
      </div>

      <div className="grid-main-side">
        <Card
          icon={ChartLine}
          title="Real-time telemetry stream flow"
          subtitle="Cross-pipeline throughput and consumer group lag."
          live
          badges={
            <>
              <Tag mono>{kafka ? "3 partitions" : "direct"}</Tag>
              <Tag tone={totalLag > 1000 ? "warn" : "good"} mono>
                Lag: {formatNumber(totalLag)}
              </Tag>
            </>
          }
          actions={
            <button type="button" className="icon-button" aria-label="More options">
              <MoreVertical size={18} />
            </button>
          }
        >
          <AreaChart
            series={[
              { name: "Frames in", values: framesIn?.lastMinutePerSecond ?? [], strong: true },
              { name: "Stored", values: stored?.lastMinutePerSecond ?? [] },
            ]}
          />
          <div className="grid-4" style={{ marginTop: 16 }}>
            <Tile label="Kafka broker ingest" icon={Server} value={framesIn?.messagesPerSecond} format={formatRate} unit="/s" foot={`${o?.topics.length ?? 0} topics`} />
            <Tile label="Redis state cache" icon={HardDrive} value={kafka ? live?.load.messagesPerSecond : o?.directFlow.messagesPerSecond} format={formatRate} unit="upd/s" foot={`${formatNumber(o?.vehiclesWithLivePosition)} keys`} />
            <Tile label="PostgreSQL sink" icon={Database} value={stored?.messagesPerSecond} format={formatRate} unit="upserts/s" foot={`${formatNumber(stored?.failures ?? 0)} failures`} />
            <Tile label="ClickHouse OLAP" icon={Table2} value="–" unit="MB/s" foot="Not built yet" />
          </div>
        </Card>

        <Card icon={Timer} title="Pipeline health" subtitle="Each stage right now." live>
          <KeyValues
            rows={[
              ["Processing flow", kafka ? "Kafka" : "Direct"],
              ["Processed positions", <span className="mono" key="p">{formatRate(processed?.messagesPerSecond ?? o?.directFlow.messagesPerSecond)}/s</span>],
              [
                "Consumer groups",
                <Tag key="g" tone={o?.consumers.every((c) => isHealthyGroupState(c.groupState)) ? "good" : "warn"}>
                  {o?.consumers.filter((c) => isHealthyGroupState(c.groupState)).length ?? 0} / {o?.consumers.length ?? 0} STABLE
                </Tag>,
              ],
              ["Kafka", o?.kafkaProblem ? <span className="bad" key="k">Not answering</span> : <span className="good" key="k">Connected</span>],
              ["Redis", o?.redisProblem ? <span className="bad" key="r">Not answering</span> : <span className="good" key="r">Connected</span>],
              ["PostgreSQL", o && o.storedPositions < 0 ? <span className="bad" key="d">Not answering</span> : <span className="good" key="d">Connected</span>],
              ["Simulator", o?.simulator.running ? `Round ${o.simulator.round} of ${o.simulator.roundsPerDrive}` : "Stopped"],
            ]}
          />
          <div className="card-foot">
            <span>Lag by consumer</span>
            <Link href="/analytics/latency">Open</Link>
          </div>
        </Card>
      </div>
    </div>
  );
}
