"use client";

import { Database, FileCheck, GitBranch, Table2, TriangleAlert, Zap } from "lucide-react";
import { Ring } from "@/components/ops/charts";
import { Card, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatPercent, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, ingress, isKafkaFlow, producer, storage } from "@/lib/pipeline";

// The checks in GpsFrameValidationRules, in the order they run.
const FIELD_RULES: [string, string][] = [
  ["imei", "Required, not blank"],
  ["recorded_at", "Required · ≤ now + 5 min"],
  ["lat / lon", "Float64 [±90, ±180]"],
  ["lat, lon = 0, 0", "Refused (no GPS fix)"],
  ["speed_kmph", "Required · 0 to 150"],
  ["(imei, recorded_at)", "Unique · stored once"],
];

export default function SchemaGatePage() {
  const { data: o } = useOverview();
  const kafka = isKafkaFlow(o);
  const framesIn = ingress(o);
  const stored = storage(o);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const dropRatio = rejected && framesIn && framesIn.totalMessages > 0 ? rejected.totalMessages / framesIn.totalMessages : 0;

  return (
    <div className="stack">
      <PageTitle
        title="Ingestion gate and dual storage sink"
        subtitle="High-throughput Kafka deserialisation with schema validation. Valid frames go on to PostgreSQL (operational state) and, next, ClickHouse (analytics); invalid ones go to the rejected-frames topic."
        right={
          <Tag tone={o && !o.kafkaProblem ? "good" : "bad"} mono>
            GATEWAY: {o && !o.kafkaProblem ? "HEALTHY" : "CHECK"}
          </Tag>
        }
      />

      <div className="grid-4">
        <Tile label="Total ingest velocity" icon={Zap} value={framesIn?.messagesPerSecond} format={formatRate} unit="events/sec" foot={`${formatNumber(framesIn?.totalMessages)} since start`} />
        <Tile
          label="Schema drop ratio"
          icon={FileCheck}
          value={dropRatio * 100}
          format={(v) => v.toFixed(3)}
          unit="% quarantined"
          foot={`${formatNumber(rejected?.totalMessages ?? 0)} of ${formatNumber(framesIn?.totalMessages)} routed to rejected`}
        />
        <Tile label="PostgreSQL writes" icon={Database} value={stored?.messagesPerSecond} format={formatRate} unit="rows/sec" foot={`${formatNumber(stored?.failures ?? 0)} failed writes`} />
        <Tile label="ClickHouse buffer flush" icon={Table2} value="50k" unit="rows / 1.84s" foot="Demo data · sink not built yet" />
      </div>

      <Card
        icon={GitBranch}
        title="Parallel schema validation gate and asynchronous demux"
        subtitle="Validate once, then fan out to every sink in parallel."
        live
        badges={<Tag mono>{kafka ? `${o?.consumers.length ?? 0} consumer groups` : "Direct flow"}</Tag>}
      >
        <div className="flow">
          <div className="flow-box">
            <div className="flow-stage">STAGE 01: INGRESS</div>
            <div className="flow-name">Kafka topic</div>
            <div className="mono muted" style={{ fontSize: 12 }}>
              gps.raw-device-frames
            </div>
            <div style={{ marginTop: 8 }}>
              <Tag mono>{formatRate(framesIn?.messagesPerSecond)} msg/s</Tag>
            </div>
          </div>
          <div className="flow-arrow">validate →</div>
          <div className="flow-box focus">
            <div className="flow-stage">STAGE 02: GATEKEEPER</div>
            <div className="flow-name">Field and bound check</div>
            <div className="grid-2" style={{ gap: 6 }}>
              <span className="tag neutral mono">IMEI</span>
              <span className="tag neutral mono">ISO-8601</span>
              <span className="tag neutral mono">Lat ±90</span>
              <span className="tag neutral mono">Lon ±180</span>
            </div>
          </div>
          <div className="flow-arrow">demux →</div>
          <div className="flow-sinks">
            <div className="flow-sink">
              <span>
                PostgreSQL worker pool
                <small>UPSERT {formatRate(stored?.messagesPerSecond)} rows/s</small>
              </span>
              <span className="dot good" />
            </div>
            <div className="flow-sink">
              <span>
                ClickHouse batch engine
                <small>Not built yet</small>
              </span>
              <span className="dot muted" />
            </div>
            <div className="flow-sink bad">
              <span>
                Rejected frames topic
                <small>{formatNumber(rejected?.totalMessages ?? 0)} frames · logged with reason</small>
              </span>
              <span className="mono">{formatPercent(dropRatio)}</span>
            </div>
          </div>
        </div>

        <div className="grid-3" style={{ marginTop: 16 }}>
          <div className="inner">
            <div className="inner-title">Strict field validations</div>
            {FIELD_RULES.map(([field, rule]) => (
              <div className="kv-row" key={field} style={{ fontSize: 12.5 }}>
                <dt className="mono">{field}</dt>
                <dd className="mono accent-text">{rule}</dd>
              </div>
            ))}
          </div>
          <div className="inner">
            <div className="inner-title">Rejected frames and failover</div>
            <div className="kv-row" style={{ fontSize: 12.5 }}>
              <dt>Rejected so far</dt>
              <dd className="mono">{formatNumber(rejected?.totalMessages ?? 0)} frames</dd>
            </div>
            <div className="kv-row" style={{ fontSize: 12.5 }}>
              <dt>Kafka retry</dt>
              <dd className="mono">Backoff, then rejected topic</dd>
            </div>
            <div className="kv-row" style={{ fontSize: 12.5 }}>
              <dt>Terminal action</dt>
              <dd className="mono">Logged with the reason</dd>
            </div>
            <p className="bad mono" style={{ fontSize: 11.5, marginTop: 10 }}>
              <TriangleAlert size={13} style={{ verticalAlign: -2, marginRight: 4 }} />
              Example: Latitude 95.0 is outside -90..90
            </p>
          </div>
          <div className="inner" style={{ display: "grid", alignContent: "space-between" }}>
            <div className="inner-title">Validated ingress purity</div>
            <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
              <Ring value={1 - dropRatio} />
              <div>
                <div className="mono" style={{ fontSize: 26, fontWeight: 500 }}>
                  {formatPercent(1 - dropRatio)}
                </div>
                <div className="muted" style={{ fontSize: 12.5 }}>
                  of frames passed validation
                </div>
              </div>
            </div>
            <div className="faint mono" style={{ fontSize: 11.5, marginTop: 10 }}>
              Failures in processing: {formatNumber(framesIn?.failures ?? 0)}
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}
