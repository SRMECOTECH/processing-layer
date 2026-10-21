"use client";

import { Table2, Zap } from "lucide-react";
import { Card, DemoButton, KeyValues, PageHeader, Progress, Tag, Tile } from "@/components/ops/kit";
import { PlannedNote } from "@/components/ops/module";

export default function ClickHousePage() {
  return (
    <div className="stack">
      <PageHeader
        title="ClickHouse"
        description="The analytics store (OLAP): append-only, columnar, for billions of coordinates, trajectory playback and fleet-wide aggregates. Planned as the second sink after schema validation."
        badges={<Tag tone="neutral">PLANNED</Tag>}
      />
      <PlannedNote what="The ClickHouse sink" />

      <Card icon={Table2} title="ClickHouse columnar sink" subtitle="Analytical append-only storage (OLAP)" live={false} badges={<Tag mono>Flush: 2.0s</Tag>}>
        <p className="muted" style={{ marginBottom: 16 }}>
          Optimised for multi-billion record coordinate scans, Kalman-filtered trajectory playback, sub-second aggregation queries and
          fleet fuel economy histograms.
        </p>
        <div className="inner" style={{ marginBottom: 16 }}>
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
            <span className="inner-title" style={{ margin: 0 }}>
              Batch flush threshold engine
            </span>
            <span className="mono accent-text" style={{ fontSize: 12 }}>
              50,000 rows / 2000ms
            </span>
          </div>
          <Progress value={0.72} />
          <div className="grid-3" style={{ marginTop: 14 }}>
            <Tile label="ZSTD ratio" value="4.82" unit="x" />
            <Tile label="Block parts" value="14" unit="active" />
            <Tile label="Insert IO" value="118" unit="MB/s" />
          </div>
        </div>
        <div className="inner-title">Table architecture and sorting key</div>
        <div className="code">
          {`telematics_raw_pings     ReplacingMergeTree()
PARTITION BY toYYYYMM(event_timestamp)
PRIMARY KEY (tenant_id, vehicle_id)
ORDER BY (tenant_id, vehicle_id, event_timestamp)
SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1`}
        </div>
        <div className="card-foot">
          <span>
            <Zap size={13} style={{ verticalAlign: -2, marginRight: 4 }} />
            100M rows scanned in 38 ms (AVX2 vectorised SIMD)
          </span>
          <span>Retention: 365 days NVMe tier</span>
        </div>
      </Card>

      <Card icon={Table2} title="Rollout plan" subtitle="What is needed to switch this sink on." live={false}>
        <KeyValues
          rows={[
            ["Consumer group", <span className="mono" key="g">gps-clickhouse-sink (new)</span>],
            ["Reads topic", <span className="mono" key="t">gps.processed-positions</span>],
            ["Batching", "50,000 rows or 2 seconds, whichever comes first"],
            ["Duplicates", "ReplacingMergeTree on (imei, recorded_at)"],
          ]}
        />
        <div className="card-actions">
          <DemoButton>ClickHouse cluster stats</DemoButton>
        </div>
      </Card>
    </div>
  );
}
