"use client";

import { Network, SplitSquareHorizontal } from "lucide-react";
import { Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { isHealthyGroupState, isKafkaFlow, stageName } from "@/lib/pipeline";

export default function ConsumerGroupsPage() {
  const { data: o } = useOverview();
  const kafka = isKafkaFlow(o);
  const consumers = o?.consumers ?? [];
  const lag = consumers.reduce((s, c) => s + c.totalLag, 0);
  const stable = consumers.filter((c) => isHealthyGroupState(c.groupState)).length;
  const members = consumers.reduce((s, c) => s + c.members.length, 0);
  const partitions = consumers.flatMap((c) => c.partitions.map((p) => ({ c, p })));

  return (
    <div className="stack">
      <PageHeader
        title="Consumer groups"
        description="One group per purpose. Each keeps its own offset on its topic and Kafka spreads the partitions across the group's consumers."
      />

      <Stats>
        <Tile label="Groups" value={o ? consumers.length : undefined} big />
        <Tile label="Stable" value={o ? stable : undefined} unit={o ? `/ ${consumers.length}` : undefined} tone={stable < consumers.length ? "warn" : "good"} big />
        <Tile label="Consumers" value={o ? members : undefined} big />
        <Tile label="Total lag" value={o ? lag : undefined} unit="messages" tone={lag > 1000 ? "bad" : undefined} big />
      </Stats>

      <Card icon={Network} title="Groups" subtitle="Rate, lag and state of every consumer group." live flush>
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
                  <th>Topic</th>
                  <th className="right">msg/s</th>
                  <th className="right">Lag</th>
                  <th className="right">Consumers</th>
                  <th>Last message</th>
                  <th>State</th>
                </tr>
              </thead>
              <tbody>
                {consumers.map((c) => (
                  <tr key={c.load.stage}>
                    <td>{stageName(c.load.stage)}</td>
                    <td className="mono muted">{c.load.consumerGroup}</td>
                    <td className="mono faint">{c.load.topic}</td>
                    <td className="right mono">{formatRate(c.load.messagesPerSecond)}</td>
                    <td className={`right mono${c.totalLag > 1000 ? " warn" : ""}`}>{formatNumber(c.totalLag)}</td>
                    <td className="right mono">{c.members.length}</td>
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

      <Card icon={SplitSquareHorizontal} title="Partition ownership" subtitle="Which consumer owns which partition, and how far it has read." live flush>
        {partitions.length === 0 ? (
          <Empty>{o ? "No partitions reported." : "Waiting for the service."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Group</th>
                  <th>Partition</th>
                  <th>Owner</th>
                  <th className="right">Committed</th>
                  <th className="right">Latest</th>
                  <th className="right">Lag</th>
                </tr>
              </thead>
              <tbody>
                {partitions.map(({ c, p }) => (
                  <tr key={`${c.load.stage}-${p.topic}-${p.partition}`}>
                    <td>{stageName(c.load.stage)}</td>
                    <td className="mono">
                      {p.topic}-{p.partition}
                    </td>
                    <td className="mono faint">{p.ownerClientId ?? "unassigned"}</td>
                    <td className="right mono">{formatNumber(p.committedOffset)}</td>
                    <td className="right mono">{formatNumber(p.latestOffset)}</td>
                    <td className={`right mono${p.lag > 1000 ? " bad" : ""}`}>{formatNumber(p.lag)}</td>
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
