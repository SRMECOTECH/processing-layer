"use client";

import { Cpu, Gauge } from "@/components/icons";
import { Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { isHealthyGroupState, isKafkaFlow, stageName } from "@/lib/pipeline";

const BEHIND = 1000;

export default function WorkersPage() {
  const { data: o } = useOverview();
  const kafka = isKafkaFlow(o);
  const rows = (o?.consumers ?? []).flatMap((c) =>
    c.partitions.map((p) => ({ consumer: c, partition: p })),
  );
  const totalLag = o?.consumers.reduce((sum, c) => sum + c.totalLag, 0) ?? 0;
  const members = o?.consumers.reduce((sum, c) => sum + c.members.length, 0) ?? 0;
  const rebalanced = o?.consumers.every((c) => isHealthyGroupState(c.groupState)) ?? false;

  return (
    <div className="stack">
      <PageHeader
        title="Workers & backpressure"
        description="Every consumer thread, the partition it owns, how far it has read and how far behind it is. Partitions are spread across consumers so one slow worker does not hold up the rest."
        badges={
          <Tag tone={rebalanced ? "good" : "warn"}>{rebalanced ? "ALL PARTITIONS BALANCED" : "REBALANCING"}</Tag>
        }
      />

      <Stats>
        <Tile label="Kafka group lag" value={totalLag} unit="messages" big tone={totalLag > BEHIND ? "bad" : undefined} />
        <Tile label="Consumer threads" value={members} big />
        <Tile label="Partitions owned" value={rows.length} big />
        <Tile label="Memory buffer pool" value="34.2" unit="% of 32 GB" foot="Example data" />
      </Stats>

      <Card icon={Cpu} title="Parallel worker health" subtitle="Kafka consumer group lag per partition" live flush>
        {!o ? (
          <Empty>Waiting for the service.</Empty>
        ) : !kafka ? (
          <Empty>The direct flow does not use Kafka partitions.</Empty>
        ) : rows.length === 0 ? (
          <Empty>Kafka has not reported any partitions yet.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Partition</th>
                  <th>Processor</th>
                  <th>Worker thread</th>
                  <th className="right">Ingest rate</th>
                  <th className="right">Offset commit</th>
                  <th className="right">Lag</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(({ consumer: c, partition: p }) => (
                  <tr key={`${c.load.stage}-${p.partition}`}>
                    <td className="mono">
                      {p.topic.replace("gps.", "")}-{String(p.partition).padStart(2, "0")}
                    </td>
                    <td>{stageName(c.load.stage)}</td>
                    <td className="mono muted">{shortClient(p.ownerClientId)}</td>
                    <td className="right mono">{formatRate(c.load.messagesPerSecond / Math.max(1, c.partitions.length))} msg/s</td>
                    <td className="right mono">{formatNumber(p.committedOffset)}</td>
                    <td className={`right mono${p.lag > BEHIND ? " bad" : ""}`}>{formatNumber(p.lag)}</td>
                    <td>
                      <Tag tone={p.lag > BEHIND ? "warn" : "accent"}>{p.lag > BEHIND ? "CATCHING UP" : "STREAMING"}</Tag>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="card-foot">
          <span>Ingest rate per partition is the group rate divided across its partitions.</span>
        </div>
      </Card>

      <Card icon={Gauge} title="Buffers and backpressure" subtitle="Memory pressure on the writers" live={false}>
        <div className="grid-3">
          <Tile label="Backpressure" value="0.00" unit="% choke rate" foot="High watermark trigger: 85%" progress={0} />
          <Tile label="Drain latency" value="3.8" unit="ms avg" foot="End-to-end Kafka to sink write" progress={0.1} />
          <Tile label="GC pause" value="<1.2" unit="ms" foot="Zero swap usage" progress={0.05} />
        </div>
      </Card>
    </div>
  );
}

function shortClient(clientId: string | null): string {
  if (!clientId) return "unassigned";
  const match = clientId.match(/-(\d+)$/);
  return match ? `worker-core-${match[1].padStart(2, "0")}` : clientId;
}
