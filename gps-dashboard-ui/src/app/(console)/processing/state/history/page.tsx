"use client";

import { useState } from "react";
import { Database, History, Server } from "@/components/icons";
import { AreaChart } from "@/components/ops/charts";
import { Card, Empty, Segmented, Tag } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { formatDistance, formatDuration, formatTime } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { VehicleTrackingState } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const STATE = moduleById("state")!;
type Store = "redis" | "kafka";
type Depth = "30" | "100" | "300";

const STORES: Record<Store, { title: string; status: string; rows: [string, string][] }> = {
  redis: {
    title: "Redis stream (in use)",
    status: "LIVE",
    rows: [
      ["Key", "gps:history:{imei} · XADD, capped with MAXLEN"],
      ["Length", "1,000 readings per vehicle"],
      ["Read by", "Any processor: XREVRANGE for the last N pings"],
      ["Speed", "Sub-millisecond, in memory"],
      ["After a restart", "Kept if Redis persists (AOF); otherwise rebuilt from Kafka"],
    ],
  },
  kafka: {
    title: "Kafka compacted topic (option)",
    status: "PLANNED",
    rows: [
      ["Topic", "gps.vehicle-state · cleanup.policy=compact, key = IMEI"],
      ["Length", "Latest state per vehicle forever; history by retention"],
      ["Read by", "Replayed into Redis on start, or a Kafka Streams state store"],
      ["Speed", "Durable and replicated, slower to query"],
      ["After a restart", "Nothing lost: the topic is the source of truth"],
    ],
  },
};

export default function HistoricalContextPage() {
  const states = usePoll<Record<string, string>[]>("/api/vehicles/states?limit=500", 15000);
  const [imei, setImei] = useState("");
  const [depth, setDepth] = useState<Depth>("100");
  const [store, setStore] = useState<Store>("redis");
  const selected = imei || states.data?.[0]?.imei || "";
  const state = usePoll<VehicleTrackingState>(selected ? `/api/vehicles/${encodeURIComponent(selected)}/state?history=${depth}` : null, 4000);
  const history = [...(state.data?.history ?? [])].reverse();
  const chosen = STORES[store];

  return (
    <div className="stack">
      <ModuleHeader
        module={STATE}
        title="Historical context"
        description="Each vehicle's recent readings, kept so processors can look back (the last 2–3 pings for speed, the last minutes for stoppage) without querying the database."
      />

      <Card
        icon={History}
        title="Readings of one vehicle"
        subtitle={selected ? `gps:history:${selected} · oldest first` : "Pick a vehicle"}
        monoSubtitle
        live
        actions={
          <div className="toolbar-row">
            <select className="select" value={selected} onChange={(e) => setImei(e.target.value)} aria-label="Vehicle">
              {(states.data ?? []).map((v) => (
                <option key={v.imei} value={v.imei}>
                  {v.vehicleRegistrationNo || v.imei}
                </option>
              ))}
            </select>
            <Segmented<Depth>
              label="Readings"
              value={depth}
              onChange={setDepth}
              options={[
                ["30", "30"],
                ["100", "100"],
                ["300", "300"],
              ]}
            />
          </div>
        }
      >
        {!selected ? (
          <Empty>{states.data ? "No vehicles yet. Start the fleet." : "Waiting for the service."}</Empty>
        ) : history.length < 2 ? (
          <Empty>{state.error ?? "Not enough history yet."}</Empty>
        ) : (
          <>
            <AreaChart
              unit="km/h"
              averageSeconds={1}
              series={[
                { name: "Speedometer", values: history.map((h) => Number(h.speedKmph)), strong: true },
                { name: "Kalman", values: history.map((h) => Number(h.kalmanSpeedKmph)) },
              ]}
              axis={[formatTime(history[0].recordedAt), `${history.length} readings`, formatTime(history[history.length - 1].recordedAt)]}
              pointLabel={(i) => formatTime(history[i]?.recordedAt)}
            />
            <div className="data-table-wrap" style={{ marginTop: 20 }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Recorded</th>
                    <th className="right">Speed</th>
                    <th className="right">From position</th>
                    <th className="right">Distance</th>
                    <th className="right">Gap</th>
                    <th>State</th>
                    <th>Ignition</th>
                  </tr>
                </thead>
                <tbody>
                  {[...history].reverse().slice(0, 12).map((h) => (
                    <tr key={h.id}>
                      <td className="mono">{formatTime(h.recordedAt)}</td>
                      <td className="right mono">{h.speedKmph} km/h</td>
                      <td className="right mono muted">{h.impliedSpeedKmph ? `${h.impliedSpeedKmph} km/h` : "–"}</td>
                      <td className="right mono">{formatDistance(Number(h.distanceMeters))}</td>
                      <td className="right mono muted">{formatDuration(Number(h.secondsSincePrevious))}</td>
                      <td>{h.movement === "MOVING" ? <Tag tone="good">MOVING</Tag> : <Tag tone="neutral">STOPPED</Tag>}</td>
                      <td className="muted">{h.ignition === "" ? "–" : h.ignition === "true" ? "on" : "off"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Card>

      <Card
        icon={store === "redis" ? Database : Server}
        title="Where the history lives"
        subtitle="Two ways to keep the context: compare them before choosing."
        actions={
          <Segmented<Store>
            label="Store"
            value={store}
            onChange={setStore}
            options={[
              ["redis", "Redis stream"],
              ["kafka", "Kafka compacted"],
            ]}
          />
        }
      >
        <div className="option-head">
          <strong>{chosen.title}</strong>
          <Tag tone={chosen.status === "LIVE" ? "good" : "neutral"}>{chosen.status}</Tag>
        </div>
        <dl className="kv">
          {chosen.rows.map(([k, v]) => (
            <div className="kv-row" key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
      </Card>
    </div>
  );
}
