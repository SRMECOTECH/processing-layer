"use client";

import { Download, ScanLine, SlidersHorizontal } from "lucide-react";
import { Card, DemoButton, PageTitle, Tag, Tile } from "@/components/ops/kit";

const ROWS: [string, string, string, string, string, "good" | "neutral" | "bad", string][] = [
  ["FLT-8892-A", "88.4 km/h", "87.9 km/h", "65.0 km/h", "Clear / Dry", "neutral", "Audit event"],
  ["FLT-3401-K", "74.1 km/h", "73.8 km/h", "55.0 km/h", "Rain alert #12", "neutral", "Audit event"],
  ["FLT-9023-P", "91.0 km/h", "42.1 km/h", "65.0 km/h", "Tamper discard", "bad", "Flag sensor"],
  ["FLT-1177-C", "52.3 km/h", "51.6 km/h", "60.0 km/h", "Clear / Dry", "good", "None"],
];

export default function AnomaliesPage() {
  return (
    <div className="stack">
      <PageTitle
        title="Violations and anomalies"
        subtitle="How well the dual verification separates real over-speeding from tampering, how often stoppages are explained by traffic or weather, and how much GPS noise the filter removes."
      />

      <Card
        icon={ScanLine}
        title="Violation and anomaly breakdown"
        subtitle="Dual-verification algorithm telemetry and GPS filtering effectiveness"
        badges={<Tag>AI ENRICHED</Tag>}
        live={false}
      >
        <div className="grid-3" style={{ marginBottom: 16 }}>
          <Tile label="Over-speed vs tamper" value="94.8" unit="%" tone="accent" foot="Speedometer matched Haversine delta · 52 tamper attempts isolated" />
          <Tile label="AI context confidence" value="96.4" unit="%" tone="accent" foot="Stoppage vs heavy traffic or storm · 789 genuine congestions" />
          <Tile label="Kalman noise delta" value="-38.2" unit="%" tone="accent" foot="Smoothed raw GPS multipath jitter · ±1.2 m accuracy" />
        </div>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Trans-ID</th>
                <th className="right">Recorded speed</th>
                <th className="right">Haversine verified</th>
                <th className="right">Zone limit</th>
                <th>Enrichment</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {ROWS.map(([id, recorded, verified, limit, enrichment, tone, action]) => (
                <tr key={id} className={tone === "bad" ? "flagged" : undefined}>
                  <td className="mono accent-text">{id}</td>
                  <td className={`right mono${Number.parseFloat(recorded) > Number.parseFloat(limit) ? " bad" : ""}`}>{recorded}</td>
                  <td className="right mono">{verified}</td>
                  <td className="right mono">{limit}</td>
                  <td>
                    <Tag tone={tone}>{enrichment}</Tag>
                  </td>
                  <td className="link">{action}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card-actions">
          <DemoButton icon={Download}>Export Parquet dump</DemoButton>
          <DemoButton primary icon={SlidersHorizontal}>
            Configure Haversine sinks
          </DemoButton>
        </div>
      </Card>
    </div>
  );
}
