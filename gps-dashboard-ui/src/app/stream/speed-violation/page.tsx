"use client";

import { useState } from "react";
import { Gauge, MoreVertical, ShieldAlert } from "lucide-react";
import { CompareChart } from "@/components/ops/charts";
import { Card, Empty, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatTime, vehicleName } from "@/lib/format";
import type { GpsPositionView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const LIMIT_KMPH = 60;

// Example trace for the demo canvas: speedometer against speed from distance over time.
const SPEEDOMETER = [20, 24, 27, 31, 36, 42, 50, 58, 64, 66, 61, 52, 47, 49, 51];
const CALCULATED = [19, 23, 27, 32, 37, 41, 49, 57, 62, 63, 60, 53, 48, 49, 50];

export default function SpeedViolationPage() {
  const [scope, setScope] = useState("hazardous");
  const readings = usePoll<GpsPositionView[]>("/api/gps-positions/recently-stored", 2000);
  const latest = readings.data ?? [];
  const over = latest.filter((r) => r.speedKmph > LIMIT_KMPH);
  const fastest = [...latest].sort((a, b) => b.speedKmph - a.speedKmph).slice(0, 6);

  return (
    <div className="stack">
      <PageTitle
        title="Speed violation"
        subtitle="Per-entity speed rules: a violation needs continuous pings over the limit for a full minute, and the speedometer must agree with the speed worked out from latitude and longitude."
      />

      <Card
        icon={Gauge}
        title="Speed violation engine"
        subtitle="Multi-ping state invalidation · Redis cache"
        monoSubtitle
        badges={<Tag mono>Continuous: 60s</Tag>}
        live={false}
        actions={
          <button type="button" className="icon-button" aria-label="More options">
            <MoreVertical size={18} />
          </button>
        }
      >
        <div className="grid-3" style={{ marginBottom: 16 }}>
          <div className="inner">
            <div className="field">
              <label className="field-label" htmlFor="scope">
                Fleet entity scope
              </label>
              <select id="scope" className="select" value={scope} onChange={(e) => setScope(e.target.value)}>
                <option value="hazardous">Hazardous material</option>
                <option value="standard">Standard cargo</option>
                <option value="passenger">Passenger</option>
              </select>
            </div>
          </div>
          <div className="inner">
            <div className="field">
              <span className="field-label">Continuous ping threshold</span>
              <span className="field-value mono">1 min &gt; {LIMIT_KMPH} km/h · TTL_60s</span>
            </div>
          </div>
          <div className="inner">
            <div className="field">
              <span className="field-label">Ping window aggregator</span>
              <span className="field-value mono">Last 3 pings required</span>
            </div>
          </div>
        </div>

        <div className="inner">
          <div style={{ display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
            <div className="legend-row">
              <span>
                <i />
                Speedometer telemetry
              </span>
              <span>
                <i className="dashed" />
                Haversine calculated
              </span>
              <span>
                <i className="limit" />
                {LIMIT_KMPH} km/h limit
              </span>
            </div>
            <Tag mono>Synced (0.4 km/h variance)</Tag>
          </div>
          <CompareChart primary={SPEEDOMETER} secondary={CALCULATED} limit={LIMIT_KMPH} flagFrom={8} flagTo={10} limitLabel={`${LIMIT_KMPH} km/h limit`} />
          <div className="area-axis mono" style={{ marginTop: 6 }}>
            <span>T-60s</span>
            <span>Now (0.0s)</span>
          </div>
        </div>

        <div className="card-foot">
          <span>
            <ShieldAlert size={14} style={{ verticalAlign: -2, marginRight: 6 }} />
            GPS spoofing and multipath guard
          </span>
          <span className="mono">0 suspicious jump spikes detected</span>
        </div>
      </Card>

      <Card
        icon={Gauge}
        title="Latest readings against the limit"
        subtitle={`The last positions stored, checked against ${LIMIT_KMPH} km/h. A real violation also needs a full minute over the limit.`}
        live
      >
        <div className="grid-3" style={{ marginBottom: 16 }}>
          <Tile label="Readings checked" value={readings.data ? latest.length : undefined} />
          <Tile label={`Over ${LIMIT_KMPH} km/h`} value={readings.data ? over.length : undefined} tone={over.length > 0 ? "bad" : undefined} />
          <Tile label="Fastest reading" value={fastest[0]?.speedKmph} unit="km/h" />
        </div>
        {fastest.length === 0 ? (
          <Empty>{readings.error ?? "Waiting for readings."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Recorded</th>
                  <th>Vehicle</th>
                  <th className="right">Speed</th>
                  <th>Against limit</th>
                </tr>
              </thead>
              <tbody>
                {fastest.map((r) => (
                  <tr key={`${r.imei}-${r.recordedAt}`} className={r.speedKmph > LIMIT_KMPH ? "flagged" : undefined}>
                    <td className="mono">{formatTime(r.recordedAt)}</td>
                    <td className="mono">{vehicleName(r.vehicleRegistrationNo, r.imei)}</td>
                    <td className="right mono">{formatNumber(r.speedKmph)} km/h</td>
                    <td>
                      {r.speedKmph > LIMIT_KMPH ? <Tag tone="bad">OVER LIMIT</Tag> : <Tag tone="good">WITHIN</Tag>}
                    </td>
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
