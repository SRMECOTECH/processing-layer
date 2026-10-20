"use client";

import { CloudRain, OctagonAlert, Power, Sparkles, TrafficCone } from "lucide-react";
import { Card, DemoButton, Empty, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { formatCoordinate, formatDateTime, formatDuration, formatNumber, vehicleName } from "@/lib/format";
import type { StopView, TripCounts } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function StoppagePage() {
  const stops = usePoll<StopView[]>("/api/stops?limit=500", 5000);
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const standing = (stops.data ?? []).filter((s) => s.status === "OPEN");
  const longest = [...standing].sort((a, b) => b.durationSeconds - a.durationSeconds).slice(0, 10);

  return (
    <div className="stack">
      <PageTitle
        title="Stoppage and ignition"
        subtitle="Stoppages are judged on time and distance travelled, then checked against traffic and weather. With the ignition off there is nothing to alarm on, so those points are neutralised."
      />

      <Card
        icon={Power}
        title="Vehicles standing now"
        subtitle="Open stops from trip detection, longest first."
        live
      >
        <div className="grid-3" style={{ marginBottom: 16 }}>
          <Tile label="Standing now" value={counts.data?.openStops} big />
          <Tile label="Stops recorded" value={counts.data?.closedStops} big />
          <Tile label="Longest standing" value={longest[0] ? formatDuration(longest[0].durationSeconds) : undefined} big />
        </div>
        {longest.length === 0 ? (
          <Empty>{stops.data ? "No vehicle is standing right now." : "Waiting for the service."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Since</th>
                  <th className="right">Standing for</th>
                  <th>Location</th>
                  <th className="right">Readings</th>
                </tr>
              </thead>
              <tbody>
                {longest.map((s) => (
                  <tr key={`${s.imei}-${s.startRecordedAt}`}>
                    <td className="mono">{vehicleName(s.vehicleRegistrationNo, s.imei)}</td>
                    <td className="muted">{formatDateTime(s.startRecordedAt)}</td>
                    <td className="right mono">{formatDuration(s.durationSeconds)}</td>
                    <td className="mono muted">{formatCoordinate(s.latitude, s.longitude)}</td>
                    <td className="right mono">{formatNumber(s.readingCount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card
        icon={OctagonAlert}
        title="Stoppage and ignition unit"
        subtitle="AI traffic and ambient context enrichment"
        monoSubtitle
        badges={<Tag mono>Heuristic v4.1</Tag>}
        live={false}
      >
        <div className="grid-2">
          <div className="inner">
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
              <strong>Ignition state: ON</strong>
              <Tag tone="ink">MONITORING</Tag>
            </div>
            <p className="muted" style={{ fontSize: 13 }}>
              Vehicle active: evaluates movement thresholds (below 2 to 3 km/h continuously). Tracks dwell time against distance
              travelled.
            </p>
            <p className="accent-text mono" style={{ marginTop: 10, fontSize: 12 }}>
              Alert trigger: &gt; 180s stationary
            </p>
          </div>
          <div className="inner">
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
              <strong>Ignition state: OFF</strong>
              <Tag tone="neutral">AUTO-NEUTRALIZE</Tag>
            </div>
            <p className="muted" style={{ fontSize: 13 }}>
              Auto-neutralises false stoppage alarms when the engine is intentionally shut down at depots or customer drop-offs.
            </p>
            <p className="mono" style={{ marginTop: 10, fontSize: 12 }}>
              Suppression rate: 99.4% accuracy
            </p>
          </div>
        </div>

        <div className="inner" style={{ marginTop: 16 }}>
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 12 }}>
            <strong>
              <Sparkles size={15} style={{ verticalAlign: -2, marginRight: 6 }} />
              Real-time AI enrichment status
            </strong>
            <span className="mono muted" style={{ fontSize: 12 }}>
              API: OK (64ms)
            </span>
          </div>
          <div className="grid-2">
            <div className="tile" style={{ background: "var(--surface)" }}>
              <div className="tile-label">
                <span>Heavy congestion: Route 101</span>
                <TrafficCone size={15} className="bad" />
              </div>
              <span className="muted" style={{ fontSize: 12.5 }}>
                Validated: delay justified (average speed 4 km/h)
              </span>
            </div>
            <div className="tile" style={{ background: "var(--surface)" }}>
              <div className="tile-label">
                <span>Heavy rain alert (14°C)</span>
                <CloudRain size={15} className="tile-icon" />
              </div>
              <span className="muted" style={{ fontSize: 12.5 }}>
                Low visibility mode: speeds capped at 50 km/h
              </span>
            </div>
          </div>
        </div>

        <div className="card-foot">
          <span>
            Suppressed alarms today: <b className="mono">1,412</b> parked units
          </span>
          <DemoButton>Configure logic rules</DemoButton>
        </div>
      </Card>
    </div>
  );
}
