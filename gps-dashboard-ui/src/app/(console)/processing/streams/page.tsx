"use client";

import { BookOpen, Database, HardDrive, Layers, ServerCog, ShieldCheck } from "lucide-react";
import { Fragment } from "react";
import { Card, Code, Empty, KeyValues, PageHeader, Progress, Stats, Tag, Tile } from "@/components/ops/kit";
import {
  ConceptRow,
  StreamsOff,
  StreamsStateTag,
  TaskGrid,
  TopologyFlow,
  formatSeconds,
  guaranteeLabel,
  useStreamsOverview,
} from "@/components/ops/streams";
import { formatAgo, formatDecimal, formatNumber, formatRate, formatTime } from "@/lib/format";
import type { StreamsApp } from "@/lib/types";

export default function StreamsTopologyPage() {
  const { data: o, error } = useStreamsOverview();
  const apps = o?.apps ?? [];
  const normalizer = apps.find((a) => a.role === "normalizer");
  const analytics = apps.find((a) => a.role === "analytics");
  const threads = apps.reduce((sum, a) => sum + (a.metrics?.aliveStreamThreads ?? 0), 0);
  const tasks = apps.reduce((sum, a) => sum + a.threads.reduce((n, t) => n + t.activeTasks.length, 0), 0);
  const standby = apps.reduce((sum, a) => sum + a.threads.reduce((n, t) => n + t.standbyTasks.length, 0), 0);
  const running = apps.filter((a) => a.state === "RUNNING").length;
  const rate = apps.reduce((sum, a) => sum + (a.metrics?.processRate ?? 0), 0);
  const s = o?.settings;

  return (
    <div className="stack">
      <PageHeader
        title="Kafka Streams"
        description="Two Kafka Streams apps run inside the service. The normalizer turns raw device frames into trusted positions, exactly-once, with each device's state in RocksDB and a changelog topic. The analytics app keeps the latest state of every vehicle and 5-minute event-time windows, read live through Interactive Queries."
        badges={o?.enabled ? <Tag tone={o.normalization === "owner" ? "good" : "warn"}>{(o.normalization ?? "off").toUpperCase()}</Tag> : undefined}
        meta={
          o?.enabled
            ? [
                ["Normalizes raw frames", o.normalizer],
                ["Instance", <Code key="i">{o.instance}</Code>],
                ["Partitions", "3 · key = IMEI"],
                ["Windows", `${formatSeconds(s?.windowSizeSeconds)} + ${formatSeconds(s?.windowGraceSeconds)} grace`],
                ["Stream threads per app", String(s?.threads ?? "–")],
              ]
            : undefined
        }
      />

      {!o?.enabled ? (
        <StreamsOff overview={o} error={error} />
      ) : (
        <>
          <Stats columns={5}>
            <Tile
              label="Apps running"
              value={running}
              unit={`/ ${apps.length}`}
              tone={running === apps.length ? "good" : "warn"}
              big
              foot={`normalizer ${normalizer?.state.toLowerCase() ?? "off"} · analytics ${analytics?.state.toLowerCase() ?? "–"}`}
            />
            <Tile label="Stream threads" value={threads} big foot="alive, across both apps" />
            <Tile label="Active tasks" value={tasks} big foot="sub-topology × partition" />
            <Tile label="Standby tasks" value={standby} big foot={standby ? "warm copies held here" : "one instance: nothing to mirror"} />
            <Tile label="Records processed" value={rate} format={formatRate} unit="/s" big />
          </Stats>

          {apps.map((app) => (
            <AppCard key={app.role} app={app} />
          ))}

          <div className="grid-2">
            <Card icon={Database} title="State stores" subtitle="Local RocksDB per task, each backed by a compacted changelog topic." live flush>
              <StoresTable apps={apps} />
            </Card>

            <Card icon={ShieldCheck} title="Fault tolerance" subtitle="Restoration from changelogs, standby replicas and thread replacement." live>
              <KeyValues
                rows={[
                  ["Standby replicas", `${s?.standbyReplicas ?? 0} ${s?.standbyReplicas ? "warm copies on other instances" : "· set 1 once there are two instances"}`],
                  ["Changelog replication", s?.replicationFactor === -1 ? "broker default" : String(s?.replicationFactor)],
                  ["State directory", <Code key="d">{s?.stateDir ?? "Kafka Streams temp folder"}</Code>],
                  ["RocksDB memory ceiling", `${s?.rocksdbOffHeapMb ?? "–"} MiB, shared by every store`],
                  ["Replaced stream threads", formatNumber(apps.reduce((n, a) => n + a.replacedThreads, 0))],
                ]}
              />
              <div className="inner-title st-section">Last restorations</div>
              <Restorations apps={apps} />
            </Card>
          </div>

          <div className="grid-2">
            <Card icon={ServerCog} title="Instances" subtitle="Every JVM running the apps, and the partitions it is active and standby for." live flush>
              <Instances apps={apps} />
            </Card>
            <Card icon={HardDrive} title="State changes" subtitle="Newest first. REBALANCING is Kafka moving tasks between threads or instances." live>
              <div className="st-timeline">
                {apps.flatMap((a) => a.stateChanges.map((c) => ({ ...c, role: a.role })))
                  .sort((a, b) => b.at.localeCompare(a.at))
                  .slice(0, 8)
                  .map((c, i) => (
                    <div className="st-timeline-row" key={i}>
                      <span className="mono faint">{formatTime(c.at)}</span>
                      <span className="st-timeline-role">{c.role}</span>
                      <span className="mono muted">{c.from}</span>
                      <span className="faint">→</span>
                      <StreamsStateTag state={c.to} />
                    </div>
                  ))}
              </div>
            </Card>
          </div>

          <Card icon={BookOpen} title="The architecture, as it runs here" subtitle="Kafka Streams architecture guide (Apache Kafka 4.3), concept by concept.">
            <div className="st-concepts">
              <ConceptRow concept="Stream partitions and tasks">
                Three partitions keyed by IMEI give each sub-topology three tasks (0_0, 0_1, 0_2). A task always owns the same partition,
                so one device&apos;s readings are processed by one task, in order. Maximum parallelism is three per app.
              </ConceptRow>
              <ConceptRow concept="Threading model">
                Each app runs {s?.threads ?? 3} stream threads, one task per thread. Threads share nothing; add an instance and Kafka moves
                tasks to it, no code change.
              </ConceptRow>
              <ConceptRow concept="Local state stores">
                previous-reading-store and seen-reading-store (normalizer), vehicle-table-store and activity-window-store (analytics), in
                RocksDB under one shared memory ceiling.
              </ConceptRow>
              <ConceptRow concept="Fault tolerance">
                Every store has a compacted changelog topic. A task that moves, or a lost state directory, is restored from it before
                processing resumes. Standby replicas keep warm copies so a fail-over does not wait for a full restore.
              </ConceptRow>
              <ConceptRow concept="Processing guarantee">
                The normalizer is exactly-once: output records, store updates and input offsets commit in one transaction. The analytics
                app is at-least-once and reads only committed records.
              </ConceptRow>
              <ConceptRow concept="Event time">
                Windows and joins run on recordedAt, the moment the device took the reading. A reading up to{" "}
                {formatSeconds(s?.windowGraceSeconds)} late still counts in its window; a final record is published once the window closes.
              </ConceptRow>
            </div>
          </Card>
        </>
      )}
    </div>
  );
}

function AppCard({ app }: { app: StreamsApp }) {
  const m = app.metrics;
  return (
    <Card
      icon={Layers}
      title={app.role === "normalizer" ? "Normalizer" : "Analytics"}
      subtitle={
        <>
          <span className="mono">{app.applicationId}</span> · {app.purpose}
        </>
      }
      badges={
        <>
          <StreamsStateTag state={app.state} />
          <Tag tone={app.guarantee?.startsWith("exactly") ? "accent" : "neutral"}>{guaranteeLabel(app.guarantee).toUpperCase()}</Tag>
        </>
      }
      live
    >
      {app.topology.subtopologies.map((sub) => (
        <Fragment key={sub.id}>
          <div className="inner-title st-section">
            Sub-topology {sub.id} · {app.threads.reduce((n, t) => n + t.activeTasks.filter((k) => k.subtopology === sub.id).length, 0)} tasks
          </div>
          <TopologyFlow sub={sub} />
        </Fragment>
      ))}
      <div className="inner-title st-section">Tasks · threads · partitions</div>
      <TaskGrid app={app} />
      <div className="st-metrics">
        <Metric label="Processed" value={`${formatRate(m?.processRate)} /s`} />
        <Metric label="Total" value={formatNumber(m?.processTotal)} />
        <Metric label="Commits" value={`${formatRate(m?.commitRate)} /s`} />
        <Metric label="Commit latency" value={`${formatDecimal(m?.commitLatencyAvgMs)} ms`} />
        <Metric label="Dropped records" value={formatNumber(m?.droppedRecordsTotal)} tone={m && m.droppedRecordsTotal > 0 ? "warn" : undefined} />
        <Metric label="Running since" value={formatAgo(app.startedAt)} />
      </div>
      {app.lastFailure && <p className="st-failure mono">Last thread failure: {app.lastFailure}</p>}
    </Card>
  );
}

function Metric({ label, value, tone }: { label: string; value: string; tone?: "warn" }) {
  return (
    <div className="st-metric">
      <span className="st-metric-label">{label}</span>
      <span className={`st-metric-value mono${tone ? ` ${tone}` : ""}`}>{value}</span>
    </div>
  );
}

function StoresTable({ apps }: { apps: StreamsApp[] }) {
  const rows = apps.flatMap((app) => {
    const stores = Array.from(new Set(app.topology.subtopologies.flatMap((s) => s.stores)));
    return stores.map((store) => {
      const lags = app.storeLags.filter((l) => l.store === store);
      return { app, store, partitions: lags.length, lag: lags.reduce((n, l) => n + l.lag, 0), entries: app.storeEntries[store] };
    });
  });
  if (rows.length === 0) return <Empty>No stores yet.</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Store</th>
            <th>App</th>
            <th className="right">Keys</th>
            <th className="right">Partitions</th>
            <th className="right">Lag</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={`${r.app.role}-${r.store}`}>
              <td className="mono">{r.store}</td>
              <td className="muted">{r.app.role}</td>
              <td className="right mono">{r.entries === undefined ? <span className="faint">window store</span> : formatNumber(r.entries)}</td>
              <td className="right mono">{r.partitions}</td>
              <td className={`right mono${r.lag > 0 ? " warn" : ""}`}>{formatNumber(r.lag)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Restorations({ apps }: { apps: StreamsApp[] }) {
  const all = apps.flatMap((a) => a.restorations).slice(0, 6);
  if (all.length === 0) return <Empty>Nothing restored yet: every store was already local.</Empty>;
  return (
    <div className="st-restores">
      {all.map((r) => (
        <div className="st-restore" key={`${r.store}-${r.partition}`}>
          <div className="st-restore-head">
            <span className="mono">
              {r.store} · p{r.partition}
            </span>
            {/* the offset range also counts transaction markers, so only a finished restore has an exact count */}
            <span className="faint mono">
              {r.done ? `${formatNumber(r.restored)} records · done` : `${formatNumber(r.restored)} / ~${formatNumber(r.recordsToRestore)}`}
            </span>
          </div>
          <Progress value={r.done || r.recordsToRestore === 0 ? 1 : r.restored / r.recordsToRestore} tone={r.done ? "good" : "warn"} />
        </div>
      ))}
    </div>
  );
}

function Instances({ apps }: { apps: StreamsApp[] }) {
  const rows = apps.flatMap((app) => app.instances.map((i) => ({ app, i })));
  if (rows.length === 0) return <Empty>No instance metadata yet.</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Instance</th>
            <th>App</th>
            <th>Active partitions</th>
            <th>Standby</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ app, i }) => (
            <tr key={`${app.role}-${i.host}-${i.port}`}>
              <td className="mono">
                {i.host}:{i.port} {i.local && <Tag tone="accent">THIS</Tag>}
              </td>
              <td className="muted">{app.role}</td>
              <td className="mono muted">{byTopic(i.activePartitions)}</td>
              <td className="mono faint">{i.standbyPartitions.length || "–"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** ["gps.raw-device-frames-0", "gps.raw-device-frames-1"] -> "raw-device-frames · p0 p1". */
function byTopic(partitions: string[]): string {
  if (partitions.length === 0) return "–";
  const topics = new Map<string, string[]>();
  for (const tp of partitions) {
    const at = tp.lastIndexOf("-");
    const topic = tp.slice(0, at).replace(/^gps\./, "");
    topics.set(topic, [...(topics.get(topic) ?? []), `p${tp.slice(at + 1)}`]);
  }
  return Array.from(topics, ([topic, ps]) => `${topic} · ${ps.join(" ")}`).join(", ");
}
