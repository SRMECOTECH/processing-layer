"use client";

import { ArrowRight, Database, Radio, Send } from "lucide-react";
import type { ReactNode } from "react";
import { formatNumber } from "@/lib/format";
import type { StreamsApp, StreamsNode, StreamsOverview, StreamsSubtopology } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import { Callout, Code, Empty, Tag, type Tone } from "./kit";

/** The Kafka Streams overview, shared by the four Kafka Streams pages. */
export function useStreamsOverview(intervalMs = 3000) {
  return usePoll<StreamsOverview>("/api/streams/overview", intervalMs);
}

/** RUNNING is the only healthy state; REBALANCING is normal for a moment (tasks are moving). */
export function streamsStateTone(state: string | undefined): Tone {
  if (state === "RUNNING") return "good";
  if (state === "REBALANCING" || state === "CREATED") return "warn";
  if (state === "ERROR") return "bad";
  return "neutral";
}

export function StreamsStateTag({ state }: { state: string | undefined }) {
  return <Tag tone={streamsStateTone(state)}>{state ?? "–"}</Tag>;
}

/** "exactly_once_v2" → "Exactly-once v2". */
export function guaranteeLabel(guarantee: string | undefined): string {
  if (!guarantee) return "–";
  if (guarantee.startsWith("exactly_once")) return "Exactly-once";
  if (guarantee === "at_least_once") return "At-least-once";
  return guarantee;
}

export function formatSeconds(seconds: number | undefined): string {
  if (seconds === undefined) return "–";
  if (seconds % 3600 === 0) return `${seconds / 3600} h`;
  if (seconds % 60 === 0) return `${seconds / 60} min`;
  return `${seconds} s`;
}

/** Shown instead of the page body when Kafka Streams is not running in this service. */
export function StreamsOff({ overview, error }: { overview: StreamsOverview | undefined; error: string | undefined }) {
  if (error && !overview) {
    return <Empty>The service did not answer: {error}</Empty>;
  }
  if (!overview) return <Empty>Waiting for the service.</Empty>;
  return (
    <Callout tone="warn" title="Kafka Streams is not running">
      {overview.reason ?? "Kafka Streams is switched off."} The listener normalizes raw frames instead. Turn it on with{" "}
      <Code>gps.streams.enabled=true</Code> in the Kafka flow.
    </Callout>
  );
}

/** Node names in the topology are lower-case and dashed ("normalize-frame"); split points end with a dash. */
function nodeLabel(node: StreamsNode): string {
  return node.name
    .split(" → ")
    .map((part) => (part.endsWith("-") ? `${part.slice(0, -1).replace(/-/g, " ")} (split)` : part.replace(/-/g, " ")))
    .join(" → ");
}

/**
 * Folds runs of plain processors (no state store, one way in, one way out) into one node, "a → b", so the flow shows
 * where state lives and where it branches without a column per mapValues.
 */
function compact(nodes: StreamsNode[]): StreamsNode[] {
  const byName = new Map(nodes.map((n) => [n.name, n]));
  const incoming = new Map<string, number>();
  nodes.forEach((n) => n.next.forEach((next) => incoming.set(next, (incoming.get(next) ?? 0) + 1)));
  const plain = (n: StreamsNode | undefined) => n !== undefined && n.kind === "PROCESSOR" && n.stores.length === 0;
  const absorbed = new Set<string>();
  const result: StreamsNode[] = [];
  for (const node of nodes) {
    if (absorbed.has(node.name)) continue;
    if (!plain(node)) {
      result.push(node);
      continue;
    }
    let tail = node;
    const names = [node.name];
    while (tail.next.length === 1) {
      const next = byName.get(tail.next[0]);
      if (!plain(next) || (incoming.get(next!.name) ?? 0) !== 1) break;
      absorbed.add(next!.name);
      names.push(next!.name);
      tail = next!;
    }
    result.push({ ...node, name: names.join(" → "), next: tail.next });
  }
  // successors that were absorbed now live inside the chain that starts before them
  const head = new Map<string, string>();
  result.forEach((n) => n.name.split(" → ").forEach((part) => head.set(part, n.name)));
  return result.map((n) => ({ ...n, next: n.next.map((next) => head.get(next) ?? next) }));
}

/** Every node's distance from the sources, so the sub-topology reads left to right as the data flows. */
function columns(sub: StreamsSubtopology): StreamsNode[][] {
  const nodes = compact(sub.nodes);
  const byName = new Map(nodes.map((n) => [n.name, n]));
  const depth = new Map<string, number>();
  const queue = nodes.filter((n) => n.kind === "SOURCE").map((n) => n.name);
  queue.forEach((name) => depth.set(name, 0));
  while (queue.length > 0) {
    const name = queue.shift()!;
    for (const next of byName.get(name)?.next ?? []) {
      const d = (depth.get(name) ?? 0) + 1;
      if (d > (depth.get(next) ?? -1)) {
        depth.set(next, d);
        queue.push(next);
      }
    }
  }
  const cols: StreamsNode[][] = [];
  for (const node of nodes) {
    const d = depth.get(node.name) ?? 0;
    (cols[d] ??= []).push(node);
  }
  return cols.filter(Boolean);
}

/** One sub-topology as a left-to-right flow: source topics, processors (with their state stores), sink topics. */
export function TopologyFlow({ sub }: { sub: StreamsSubtopology }) {
  const cols = columns(sub);
  return (
    <div className="st-flow-wrap">
      <div className="st-flow" role="img" aria-label={`Sub-topology ${sub.id}: ${sub.sourceTopics.join(", ")} to ${sub.sinkTopics.join(", ")}`}>
        {cols.map((col, i) => (
          <div className="st-col-group" key={i}>
            {i > 0 && (
              <span className="st-arrow" aria-hidden="true">
                <ArrowRight size={14} />
              </span>
            )}
            <div className="st-col">
              {col.map((node) => (
                <FlowNode key={node.name} node={node} />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function FlowNode({ node }: { node: StreamsNode }) {
  if (node.kind === "SOURCE" || node.kind === "SINK") {
    const Icon = node.kind === "SOURCE" ? Radio : Send;
    return (
      <div className={`st-node ${node.kind.toLowerCase()}`}>
        <span className="st-node-kind">
          <Icon size={12} /> {node.kind === "SOURCE" ? "source" : "sink"}
        </span>
        {node.topics.map((t) => (
          <span key={t} className="st-node-topic mono">
            {t}
          </span>
        ))}
      </div>
    );
  }
  return (
    <div className={`st-node processor${node.stores.length > 0 ? " stateful" : ""}`}>
      <span className="st-node-kind">{node.stores.length > 0 ? "stateful processor" : "processor"}</span>
      <span className="st-node-name">{nodeLabel(node)}</span>
      {node.stores.map((s) => (
        <span key={s} className="st-store mono">
          <Database size={11} /> {s}
        </span>
      ))}
    </div>
  );
}

/**
 * Tasks of one app as a grid: one column per partition, the task that owns it, the thread that runs it and how far
 * it has read. Kafka Streams creates exactly one task per sub-topology and input partition.
 */
export function TaskGrid({ app }: { app: StreamsApp }) {
  const tasks = app.threads.flatMap((thread) =>
    thread.activeTasks.map((task) => ({ task, thread: thread.name, standby: false })).concat(
      thread.standbyTasks.map((task) => ({ task, thread: thread.name, standby: true })),
    ),
  );
  if (tasks.length === 0) {
    return <Empty>No tasks assigned yet{app.state === "REBALANCING" ? ": the group is rebalancing." : "."}</Empty>;
  }
  const threads = Array.from(new Set(app.threads.map((t) => t.name)));
  tasks.sort((a, b) => Number(a.standby) - Number(b.standby) || a.task.id.localeCompare(b.task.id));
  return (
    <div className="st-tasks">
      {tasks.map(({ task, thread, standby }) => {
        const lag = task.inputs.reduce((sum, i) => sum + (i.lag ?? 0), 0);
        return (
          <div key={`${task.id}-${standby}`} className={`st-task t${threads.indexOf(thread) % 4}${standby ? " standby" : ""}`}>
            <div className="st-task-head">
              <span className="st-task-id mono">task {task.id}</span>
              <Tag tone={standby ? "neutral" : "accent"}>{standby ? "STANDBY" : "ACTIVE"}</Tag>
            </div>
            <div className="st-task-thread">{thread}</div>
            {task.inputs.map((input) => (
              <div key={`${input.topic}-${input.partition}`} className="st-task-input mono">
                <span title={`${input.topic}-${input.partition}`}>
                  {input.topic.replace(/^gps\./, "")} · p{input.partition}
                </span>
                <span className={lag > 1000 ? "warn" : "faint"}>lag {formatNumber(input.lag)}</span>
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

/** A labelled concept from the architecture guide next to what it is here. */
export function ConceptRow({ concept, children }: { concept: string; children: ReactNode }) {
  return (
    <div className="st-concept">
      <span className="st-concept-name">{concept}</span>
      <span className="st-concept-text">{children}</span>
    </div>
  );
}
