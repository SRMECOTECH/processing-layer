"use client";

import Link from "next/link";
import { ArrowRight, ListOrdered, Radio, SlidersHorizontal } from "lucide-react";
import type { ReactNode } from "react";
import { formatNumber, formatRate } from "@/lib/format";
import { EVENTS_TOPIC, OUTPUT_TOPIC, PROCESSORS, BUSINESS_RULES, SINKS, type PipelineModule } from "@/lib/modules";
import { useOverview } from "@/lib/overview-context";
import { STAGE, consumer, isKafkaFlow, producer } from "@/lib/pipeline";
import type { Feed } from "@/lib/nav";
import type { EngineOverview } from "@/lib/types";
import { Callout, Card, Code, FeedTag, PageHeader, Tag } from "./kit";

/** Live load of a module: messages per second of its first stage and the lag of all its stages. */
export function moduleLoad(o: EngineOverview | undefined, m: PipelineModule): { rate?: number; lag?: number } {
  if (!o || m.stages.length === 0) return {};
  const groups = m.stages.map((s) => consumer(o, s)).filter((c) => c !== undefined);
  if (groups.length === 0) return isKafkaFlow(o) ? {} : { rate: o.directFlow.messagesPerSecond, lag: 0 };
  return { rate: groups[0].load.messagesPerSecond, lag: groups.reduce((sum, c) => sum + c.totalLag, 0) };
}

/** Page header for a module page: status, consumer group, topic, state keys and where its settings live. */
export function ModuleHeader({
  module: m,
  title,
  description,
  actions,
  status,
}: {
  module: PipelineModule;
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  /** When this page is at a different stage than the module (a planned page of a built module). */
  status?: Feed;
}) {
  return (
    <PageHeader
      title={title}
      description={description ?? m.summary}
      badges={<FeedTag feed={status ?? m.status} />}
      actions={actions}
      meta={[
        ["Consumer group", <span key="g" className="meta-codes">{m.consumerGroups.map((g) => <Code key={g}>{g}</Code>)}</span>],
        ["Reads", <Code key="r">{m.reads}</Code>],
        ["State", <span key="s" className="meta-codes">{m.state.slice(0, 2).map((s) => <Code key={s}>{s}</Code>)}{m.state.length > 2 && <span className="faint">+{m.state.length - 2}</span>}</span>],
        ["Configuration", m.config],
      ]}
    />
  );
}

/** The module's logic as numbered steps, with what it emits. */
export function HowItWorks({ module: m, extra }: { module: PipelineModule; extra?: ReactNode }) {
  return (
    <Card icon={ListOrdered} title="How it works" subtitle="The logic this processor applies to every reading, in order.">
      <ol className="steps">
        {m.logic.map((step, i) => (
          <li key={i}>
            <span className="step-n">{i + 1}</span>
            <span>{step}</span>
          </li>
        ))}
      </ol>
      <div className="card-foot">
        <span className="meta-codes">
          Emits {m.emits.map((e) => <Code key={e}>{e}</Code>)}
        </span>
        {extra}
      </div>
    </Card>
  );
}

export type Setting = { label: string; value: ReactNode; unit?: string; hint?: string };

/** Settings shown as a calm grid; `source` says where they are changed. */
export function SettingsCard({
  title,
  subtitle,
  source,
  settings,
  live,
  actions,
}: {
  title: string;
  subtitle?: string;
  source: string;
  settings: Setting[];
  live?: boolean;
  actions?: ReactNode;
}) {
  return (
    <Card icon={SlidersHorizontal} title={title} subtitle={subtitle} live={live} actions={actions} badges={<Tag tone="neutral" mono>{source}</Tag>}>
      <div className="settings-grid">
        {settings.map((s) => (
          <div className="setting-cell" key={s.label}>
            <div className="setting-cell-label">{s.label}</div>
            <div className="setting-cell-value">
              <span className={`mono${typeof s.value === "string" && s.value.length > 12 ? " long" : ""}`}>{s.value}</span>
              {s.unit && <span className="tile-unit">{s.unit}</span>}
            </div>
            {s.hint && <div className="setting-cell-hint">{s.hint}</div>}
          </div>
        ))}
      </div>
    </Card>
  );
}

/** The note at the top of a module that has no backend yet. */
export function PlannedNote({ what }: { what: string }) {
  return (
    <Callout tone="planned" title="Planned module">
      {what} is not built yet. This page shows the layout it will have, filled with example data, so the backend can be built to match.
    </Callout>
  );
}

/** One processor as a card: status, what it does, its live load and a link into it. */
export function ModuleCard({ module: m }: { module: PipelineModule }) {
  const { data: o } = useOverview();
  const { rate, lag } = moduleLoad(o, m);
  const Icon = m.icon;
  return (
    <Link href={m.href} className={`module-card${m.status === "planned" ? " planned" : ""}`}>
      <div className="module-card-head">
        <span className="card-icon">
          <Icon size={17} />
        </span>
        <FeedTag feed={m.status} />
      </div>
      <div className="module-card-name">{m.name}</div>
      <p className="module-card-text">{m.summary}</p>
      <div className="module-card-foot">
        {m.status === "planned" ? (
          <span className="faint">Not built yet</span>
        ) : (
          <span className="mono">
            {formatRate(rate)} <span className="faint">msg/s</span> · {formatNumber(lag)} <span className="faint">lag</span>
          </span>
        )}
        <span className="module-card-open">
          Open <ArrowRight size={14} />
        </span>
      </div>
    </Link>
  );
}

/**
 * The pipeline after Kafka as a diagram: output topic, the parallel processors, business logic,
 * ingestion control and the two stores. Planned parts are drawn dashed; every node opens its page.
 */
export function PipelineDiagram() {
  const { data: o } = useOverview();
  const kafka = isKafkaFlow(o);
  const out = kafka ? producer(o, STAGE.processedPositionProducer) : o?.directFlow;
  const events = producer(o, "VEHICLE_EVENT_PRODUCER");
  const flowing = (out?.messagesPerSecond ?? 0) > 0;

  return (
    <div className="pipe-wrap">
    <div className={`pipe${flowing ? " flowing" : ""}`}>
      <div className="pipe-col">
        <div className="pipe-col-title">Kafka output</div>
        <div className="pipe-stack">
          <Link href="/processing/consumers" className="pipe-node source">
            <span className="pipe-node-top">
              <Radio size={15} />
              <span className="pipe-node-name">Processed positions</span>
            </span>
            <span className="pipe-node-sub mono">{OUTPUT_TOPIC}</span>
            <span className="pipe-node-rate mono">{formatRate(out?.messagesPerSecond)} msg/s</span>
            <span className="pipe-node-sub">3 partitions · key = IMEI</span>
          </Link>
        </div>
      </div>
      <PipeLink />
      <div className="pipe-col wide">
        <div className="pipe-col-title">Parallel processors · one consumer group each</div>
        <div className="pipe-stack bus">
          {PROCESSORS.map((m) => (
            <PipeNode key={m.id} module={m} overview={o} />
          ))}
        </div>
      </div>
      <PipeLink />
      <div className="pipe-col">
        <div className="pipe-col-title">Business logic</div>
        <div className="pipe-stack bus">
          {BUSINESS_RULES.map((m) => (
            <PipeNode key={m.id} module={m} overview={o} />
          ))}
        </div>
        <div className="pipe-note mono">
          {EVENTS_TOPIC} · {formatRate(events?.messagesPerSecond)} /s
        </div>
      </div>
      <PipeLink />
      <div className="pipe-col">
        <div className="pipe-col-title">Ingestion control → stores</div>
        <div className="pipe-stack bus">
          {SINKS.map((m) => (
            <PipeNode key={m.id} module={m} overview={o} />
          ))}
        </div>
      </div>
    </div>
    </div>
  );
}

function PipeNode({ module: m, overview }: { module: PipelineModule; overview: EngineOverview | undefined }) {
  const { rate } = moduleLoad(overview, m);
  const Icon = m.icon;
  return (
    <Link href={m.href} className={`pipe-node ${m.status}`} title={m.summary}>
      <span className="pipe-node-top">
        <Icon size={15} />
        <span className="pipe-node-name">{m.name}</span>
        <span className={`status-dot ${m.status === "planned" ? "muted" : m.status === "partial" ? "warn" : rate ? "good" : "muted"}`} />
      </span>
      <span className="pipe-node-sub">{m.status === "planned" ? "planned" : `${formatRate(rate)} msg/s`}</span>
    </Link>
  );
}

function PipeLink() {
  return (
    <div className="pipe-link" aria-hidden="true">
      <svg viewBox="0 0 40 12" preserveAspectRatio="none">
        <line x1="0" y1="6" x2="34" y2="6" />
        <path d="M 32 1 L 39 6 L 32 11" />
      </svg>
    </div>
  );
}
