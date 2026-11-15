"use client";

import Link from "next/link";
import { useState } from "react";
import { Boxes, RotateCcw } from "@/components/icons";
import { Callout, Card, Code, Empty, FeedTag, PageHeader, Tag } from "@/components/ops/kit";
import { formatAgo, formatNumber, formatRate } from "@/lib/format";
import { BUSINESS_RULES, PROCESSORS, SINKS, type PipelineModule } from "@/lib/modules";
import { usePoll } from "@/lib/use-poll";

/** GET /api/modules: every GpsModule on the service's class path. */
type PluggedModule = {
  id: string;
  name: string;
  summary: string;
  consumerGroup: string;
  stage: string | null;
  order: number;
  emits: string[];
  statePatterns: string[];
  enabled: boolean;
  running: boolean;
  flowMode: "kafka" | "direct";
  reads: string;
  messagesPerSecond: number;
  totalMessages: number;
  failures: number;
  lastMessageAt: string | null;
  lag: number | null;
};

type ReplayReport = { module: string; consumerGroup: string; from: string; stateKeysDeleted: number; readingsToReplay: number };

/**
 * The modules plugged into the service right now (gps-module-api): one interface, one config line each, and the
 * platform gives every one its own consumer group, retry / dead letters, load counters and replay.
 */
function PluggedModules() {
  const poll = usePoll<PluggedModule[]>("/api/modules", 3000);
  const [hours, setHours] = useState(2);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{ tone: "info" | "warn"; text: string } | null>(null);

  const replay = async (m: PluggedModule) => {
    const from = new Date(Date.now() - hours * 3_600_000).toISOString();
    if (!window.confirm(`Replay ${m.name} from ${hours} h ago? Its consumers stop for a moment, its Redis state is rebuilt, and every reading since then is handed to it again.`)) return;
    setBusy(m.id);
    setMessage(null);
    try {
      const response = await fetch(`/api/modules/${m.id}/replay?from=${encodeURIComponent(from)}`, { method: "POST" });
      const body = (await response.json()) as ReplayReport & { error?: string };
      if (!response.ok) throw new Error(body.error ?? `The service answered ${response.status}`);
      setMessage({ tone: "info", text: `${m.name}: ${formatNumber(body.readingsToReplay)} readings since ${new Date(body.from).toLocaleString()} are being replayed (${body.stateKeysDeleted} state keys rebuilt).` });
      poll.refresh();
    } catch (problem) {
      setMessage({ tone: "warn", text: problem instanceof Error ? problem.message : "Replay failed" });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Card
      icon={Boxes}
      title="Plugged-in modules (live)"
      subtitle="Each is a GpsModule bean with its own consumer group on gps.processed-positions; switch one off with gps.modules.<id>.enabled=false"
      live={!poll.error}
      flush
      actions={
        <label className="toolbar-row">
          <span className="faint">Replay from</span>
          <select className="select" value={hours} onChange={(e) => setHours(Number(e.target.value))} aria-label="Replay window">
            <option value={1}>1 h ago</option>
            <option value={2}>2 h ago</option>
            <option value={6}>6 h ago</option>
            <option value={24}>24 h ago</option>
            <option value={24 * 7}>7 days ago</option>
          </select>
        </label>
      }
    >
      {message && (
        <div style={{ padding: "0 var(--card-pad) 12px" }}>
          <Callout tone={message.tone}>{message.text}</Callout>
        </div>
      )}
      {!poll.data ? (
        <Empty>{poll.error ?? "Asking the service."}</Empty>
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Module</th>
                <th>State</th>
                <th>Consumer group</th>
                <th className="right">msg/s</th>
                <th className="right">Handled</th>
                <th className="right">Lag</th>
                <th>Last reading</th>
                <th>Emits</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {poll.data.map((m) => (
                <tr key={m.id}>
                  <td>
                    <strong>{m.name}</strong>
                    <div className="faint" style={{ fontSize: 11.5, whiteSpace: "normal", maxWidth: 320 }}>
                      {m.summary}
                    </div>
                  </td>
                  <td>{!m.enabled ? <Tag tone="neutral">off</Tag> : m.running ? <Tag tone="good">running</Tag> : <Tag tone="warn">stopped</Tag>}</td>
                  <td>
                    <Code>{m.consumerGroup}</Code>
                  </td>
                  <td className="right mono">{formatRate(m.messagesPerSecond)}</td>
                  <td className="right mono">
                    {formatNumber(m.totalMessages)}
                    {m.failures > 0 && <div className="bad">{formatNumber(m.failures)} failed</div>}
                  </td>
                  <td className="right mono">{m.lag === null ? "–" : formatNumber(m.lag)}</td>
                  <td className="muted">{m.lastMessageAt ? formatAgo(m.lastMessageAt) : "–"}</td>
                  <td className="muted wrap">{m.emits.join(", ") || "–"}</td>
                  <td>
                    <button type="button" className="btn soft" disabled={!m.enabled || m.flowMode !== "kafka" || busy !== null} onClick={() => replay(m)}>
                      <RotateCcw size={13} /> {busy === m.id ? "Replaying…" : "Replay"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

const GROUPS: [string, PipelineModule[]][] = [
  ["Processors", PROCESSORS],
  ["Business logic", BUSINESS_RULES],
  ["Ingestion and stores", SINKS],
];

export default function ModuleRegistryPage() {
  return (
    <div className="stack">
      <PageHeader
        title="Module registry"
        description="Every module after the Kafka output topic. On top, the modules actually plugged into the service (GET /api/modules); below, the console's descriptions in src/lib/modules.ts, which the pipeline diagram and module pages read."
      />

      <PluggedModules />

      {GROUPS.map(([title, modules]) => (
        <Card key={title} icon={Boxes} title={title} subtitle={`${modules.length} modules`} flush>
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Module</th>
                  <th>Status</th>
                  <th>Consumer group</th>
                  <th>State</th>
                  <th>Configuration</th>
                  <th>Emits</th>
                </tr>
              </thead>
              <tbody>
                {modules.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <Link className="link" href={m.href}>
                        {m.name}
                      </Link>
                    </td>
                    <td>
                      <FeedTag feed={m.status} />
                    </td>
                    <td>
                      <span className="meta-codes">
                        {m.consumerGroups.map((g) => (
                          <Code key={g}>{g}</Code>
                        ))}
                      </span>
                    </td>
                    <td>
                      <span className="meta-codes">
                        {m.state.map((s) => (
                          <Code key={s}>{s}</Code>
                        ))}
                      </span>
                    </td>
                    <td className="muted wrap">{m.config}</td>
                    <td className="muted wrap">{m.emits.join(", ") || "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      ))}
    </div>
  );
}
