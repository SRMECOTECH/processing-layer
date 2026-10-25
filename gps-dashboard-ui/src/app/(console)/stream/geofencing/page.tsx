"use client";

import { Grid3x3, Layers } from "lucide-react";
import { Card, DemoButton, KeyValues, PageTitle, Tag, Tile } from "@/components/ops/kit";

const ZONES: [string, string, string, string][] = [
  ["Metro Industrial Zone", "Outer · 12.4 km²", "384 inside", "0 breaches"],
  ["Loading Dock 4B", "Inner · nested in Metro", "18 inside", "14 min average dwell"],
  ["Cold Storage Yard", "Inner · nested in Metro", "6 inside", "1 overstay"],
  ["Port Gate 3", "Outer · 2.1 km²", "41 inside", "0 breaches"],
];

export default function GeofencingPage() {
  return (
    <div className="stack">
      <PageTitle
        title="Nested geofencing"
        subtitle="Zones inside zones: a vehicle can be inside an industrial area and, within it, at a specific dock. Distances are measured to the nearest boundary of each level."
      />

      <Card
        icon={Grid3x3}
        title="Spatial and trajectory processors"
        subtitle="Nested geofencing · Kalman GPS filter"
        monoSubtitle
        badges={<Tag mono>PostGIS + Turf engine</Tag>}
        live={false}
      >
        <svg className="geo" viewBox="0 0 1000 200" preserveAspectRatio="none" role="img" aria-label="Outer zone with a nested dock and a vehicle track">
          <polygon className="outer" points="60,24 940,40 900,186 110,176" />
          <polygon className="inner-zone" points="430,70 640,64 630,150 420,146" />
          <path className="track" d="M 0 120 L 240 128 L 520 108 L 760 118 L 1000 132" />
          <circle className="ping" cx="240" cy="128" r="5" />
          <circle className="ping" cx="760" cy="118" r="5" />
          <circle className="vehicle" cx="520" cy="108" r="9" />
          <text x="72" y="44">OUTER: Metro Industrial Zone (12.4 km²)</text>
          <text x="440" y="88">INNER: Loading Dock 4B</text>
          <text x="800" y="172">Tolerance: ±15 m corridor</text>
        </svg>
        <div className="grid-3" style={{ marginTop: 16 }}>
          <Tile label="Outer boundary" value="384" unit="units" foot="Inside the industrial zone" />
          <Tile label="Inner dock 4B" value="18" unit="units" foot="Dwell 14 min on average" />
          <Tile label="Kalman smoothing" value="0.994" unit="R²" foot="Jitter suppressed" />
        </div>
        <div className="card-foot">
          <span>
            Kalman trip distance accuracy: <b className="mono">99.8%</b> against ground truth
          </span>
          <DemoButton>Inspect geometries</DemoButton>
        </div>
      </Card>

      <Card icon={Layers} title="Zones" subtitle="Each zone and the zones nested inside it." live={false}>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Zone</th>
                <th>Level</th>
                <th>Vehicles</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {ZONES.map(([name, level, inside, status]) => (
                <tr key={name}>
                  <td>{name}</td>
                  <td className="muted">{level}</td>
                  <td className="mono">{inside}</td>
                  <td className={status.includes("overstay") ? "warn" : "muted"}>{status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ marginTop: 16 }}>
          <KeyValues
            rows={[
              ["Containment check", <span className="mono" key="c">ST_Contains on every position</span>],
              ["Nesting depth", "Up to 3 levels"],
              ["Boundary tolerance", "15 m"],
            ]}
          />
        </div>
      </Card>
    </div>
  );
}
