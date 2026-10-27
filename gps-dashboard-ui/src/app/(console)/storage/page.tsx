"use client";

import Link from "next/link";
import { Database, FileCheck, GitBranch, Table2, Zap } from "@/components/icons";
import { Ring } from "@/components/ops/charts";
import { Callout, Card, Code, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatPercent, formatRate } from "@/lib/format";
import { OUTPUT_TOPIC } from "@/lib/modules";
import { useOverview } from "@/lib/overview-context";
import { STAGE, ingress, isKafkaFlow, producer, storage } from "@/lib/pipeline";

export default function IngestionOverviewPage() {
  const { data: o } = useOverview();
  const kafka = isKafkaFlow(o);
  const framesIn = ingress(o);
  const stored = storage(o);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const dropRatio = rejected && framesIn && framesIn.totalMessages > 0 ? rejected.totalMessages / framesIn.totalMessages : 0;

  return (
    <div className="stack">
      <PageHeader
        title="Ingestion overview"
        description="After the processors have done as much as they can in parallel, ingestion control checks every record against the schema, then writes it to both stores: PostgreSQL for operations and ClickHouse for analytics. Anything that fails goes to the rejected topic with its reason."
        badges={<Tag tone={o && !o.kafkaProblem ? "good" : "neutral"}>{o ? (o.kafkaProblem ? "CHECK KAFKA" : "HEALTHY") : "CONNECTING"}</Tag>}
        meta={[
          ["Reads", <Code key="r">{OUTPUT_TOPIC}</Code>],
          ["Rejects to", <Code key="j">gps.rejected-frames</Code>],
          ["Stores", "PostgreSQL · ClickHouse (planned)"],
        ]}
      />

      <Stats>
        <Tile label="Records in" icon={Zap} value={framesIn?.messagesPerSecond} format={formatRate} unit="/s" big foot={`${formatNumber(framesIn?.totalMessages)} since start`} />
        <Tile label="Failed the schema" icon={FileCheck} value={rejected?.totalMessages ?? (o ? 0 : undefined)} big foot={`${formatPercent(dropRatio)} of records`} href="/storage/rejected" />
        <Tile label="PostgreSQL writes" icon={Database} value={stored?.messagesPerSecond} format={formatRate} unit="rows/s" big foot={`${formatNumber(stored?.failures ?? 0)} failed writes`} href="/storage/postgres" />
        <Tile label="ClickHouse writes" icon={Table2} value="–" big foot="sink not built yet" href="/storage/clickhouse" />
      </Stats>

      <Card icon={GitBranch} title="Validate once, write twice" subtitle="The gate fans valid records out to both stores in parallel." live badges={<Tag tone="neutral" mono>{kafka ? `${o?.consumers.length ?? 0} consumer groups` : "direct flow"}</Tag>}>
        <div className="flow">
          <div className="flow-box">
            <div className="flow-stage">1 · Processed records</div>
            <div className="flow-name">Kafka output</div>
            <div className="mono muted small">{OUTPUT_TOPIC}</div>
            <div className="flow-figure mono">{formatRate(framesIn?.messagesPerSecond)} /s</div>
          </div>
          <div className="flow-arrow">→</div>
          <Link href="/storage/schema" className="flow-box focus">
            <div className="flow-stage">2 · Ingestion control</div>
            <div className="flow-name">Schema validation</div>
            <div className="flow-tags">
              <span className="code-chip">imei</span>
              <span className="code-chip">recorded_at</span>
              <span className="code-chip">lat ±90</span>
              <span className="code-chip">lon ±180</span>
              <span className="code-chip">speed 0–150</span>
            </div>
          </Link>
          <div className="flow-arrow">→</div>
          <div className="flow-sinks">
            <Link href="/storage/postgres" className="flow-sink">
              <span>
                PostgreSQL
                <small>{formatRate(stored?.messagesPerSecond)} rows/s · upsert on (imei, recorded_at)</small>
              </span>
              <span className={`status-dot ${o && o.storedPositions >= 0 ? "good" : "muted"}`} />
            </Link>
            <Link href="/storage/clickhouse" className="flow-sink planned">
              <span>
                ClickHouse
                <small>planned · batches of 50,000 rows or 2 s</small>
              </span>
              <span className="status-dot muted" />
            </Link>
            <Link href="/storage/rejected" className="flow-sink bad">
              <span>
                Rejected topic
                <small>{formatNumber(rejected?.totalMessages ?? 0)} records with their reason</small>
              </span>
              <span className="mono">{formatPercent(dropRatio)}</span>
            </Link>
          </div>
        </div>

        <div className="purity">
          <Ring value={1 - dropRatio} />
          <div>
            <div className="purity-value mono">{formatPercent(1 - dropRatio)}</div>
            <div className="muted">of records passed validation</div>
          </div>
        </div>
      </Card>

      <Callout title="Where validation runs today">
        The field checks currently run when raw frames are processed (consumer gps-raw-frame-processing-service), before the output topic.
        The planned ingestion gate repeats the contract check right before the stores, so a processor bug can never write a bad row.
      </Callout>
    </div>
  );
}
