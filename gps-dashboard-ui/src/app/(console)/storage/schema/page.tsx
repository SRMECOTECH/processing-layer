"use client";

import { FileCheck, FileJson } from "@/components/icons";
import { Card, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatPercent } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, ingress, producer } from "@/lib/pipeline";

// The checks of GpsFrameValidationRules, in the order they run.
const RULES: [string, string, string][] = [
  ["imei", "string", "Required, not blank"],
  ["recorded_at", "timestamp", "Required, not more than 5 minutes in the future"],
  ["latitude", "float64", "Required, −90 … 90"],
  ["longitude", "float64", "Required, −180 … 180"],
  ["latitude, longitude", "pair", "Not 0, 0 (the device has no GPS fix)"],
  ["speed_kmph", "float64", "Required, 0 … 150"],
  ["(imei, recorded_at)", "key", "Unique: a repeated reading is stored once"],
];

// The record the ingestion gate will check before writing to either store.
const CONTRACT = `{
  "imei": "string",
  "tenant_code": "string | null",
  "vehicle_registration_no": "string | null",
  "recorded_at": "timestamp (UTC)",
  "latitude": "float64",
  "longitude": "float64",
  "speed_kmph": "float64",
  "movement_status": "MOVING | STOPPED",
  "distance_from_previous_m": "float64",
  "distance_source": "FIRST_READING | GPS | DEVICE_ODOMETER | JITTER_FILTERED | JUMP_FILTERED",
  "ignition": "boolean | null",
  "kalman_distance_m": "float64",
  "schema_version": 2
}`;

export default function SchemaValidationPage() {
  const { data: o } = useOverview();
  const framesIn = ingress(o);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const total = framesIn?.totalMessages ?? 0;
  const bad = rejected?.totalMessages ?? 0;

  return (
    <div className="stack">
      <PageHeader
        title="Schema validation"
        description="The contract every record must meet before it is written. A record that breaks any rule is not stored; it goes to gps.rejected-frames with the first rule it broke."
      />

      <Stats columns={3}>
        <Tile label="Checked" value={o ? total : undefined} big />
        <Tile label="Passed" value={o ? total - bad : undefined} tone="good" big foot={total ? formatPercent((total - bad) / total) : undefined} />
        <Tile label="Rejected" value={o ? bad : undefined} tone={bad ? "warn" : undefined} big href="/storage/rejected" />
      </Stats>

      <Card icon={FileCheck} title="Field rules" subtitle="GpsFrameValidationRules, in the order they run." live flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: 40 }}>#</th>
                <th>Field</th>
                <th>Type</th>
                <th>Rule</th>
                <th>Enforced</th>
              </tr>
            </thead>
            <tbody>
              {RULES.map(([field, type, rule], i) => (
                <tr key={field}>
                  <td className="mono faint">{i + 1}</td>
                  <td className="mono">{field}</td>
                  <td className="mono muted">{type}</td>
                  <td>{rule}</td>
                  <td>
                    <Tag tone="good">YES</Tag>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card-foot">
          <span>{formatNumber(total)} records checked since the service started</span>
        </div>
      </Card>

      <Card icon={FileJson} title="Storage contract" subtitle="The processed record the gate will check before PostgreSQL and ClickHouse." live={false} badges={<Tag tone="neutral">v2 · PLANNED</Tag>}>
        <pre className="code">{CONTRACT}</pre>
      </Card>
    </div>
  );
}
