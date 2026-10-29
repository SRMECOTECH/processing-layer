"use client";

import { useState } from "react";
import { GitCompareArrows } from "@/components/icons";
import { Card, DemoButton, PageHeader, Tag } from "@/components/ops/kit";

const CHANNELS = ["In-app", "Email", "SMS", "Push", "Webhook"];
const RULES: { alert: string; source: string; severity: string; on: boolean[] }[] = [
  { alert: "Over-speed, GPS verified", source: "Speed violation", severity: "HIGH", on: [true, true, false, true, true] },
  { alert: "Speedometer suspect", source: "Speed violation", severity: "MEDIUM", on: [true, true, false, false, false] },
  { alert: "Unexplained stoppage", source: "Stoppage & ignition", severity: "HIGH", on: [true, false, true, false, true] },
  { alert: "Idling over 5 min", source: "Stoppage & ignition", severity: "LOW", on: [true, false, false, true, false] },
  { alert: "Geofence dwell", source: "Nested geofencing", severity: "MEDIUM", on: [true, true, false, false, false] },
  { alert: "Waypoint missed", source: "Waypoints", severity: "HIGH", on: [true, true, false, false, true] },
  { alert: "Route deviation", source: "Route deviation", severity: "CRITICAL", on: [true, true, true, true, true] },
  { alert: "Platform problem", source: "Engine", severity: "HIGH", on: [true, true, false, false, true] },
];

export default function RoutingRulesPage() {
  const [matrix, setMatrix] = useState(RULES.map((r) => r.on));

  return (
    <div className="stack">
      <PageHeader
        title="Routing rules"
        description="Which alert goes to which channel. Each row is one kind of alert from the business logic; tick the channels it should reach."
        badges={<Tag tone="neutral">PLANNED</Tag>}
        actions={<DemoButton primary>Save rules</DemoButton>}
      />

      <Card icon={GitCompareArrows} title="Alert × channel" subtitle="Example data; changes stay on this page." live={false} flush>
        <div className="data-table-wrap">
          <table className="data-table matrix">
            <thead>
              <tr>
                <th>Alert</th>
                <th>From</th>
                <th>Severity</th>
                {CHANNELS.map((c) => (
                  <th key={c} className="center">
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {RULES.map((r, i) => (
                <tr key={r.alert}>
                  <td>
                    <strong>{r.alert}</strong>
                  </td>
                  <td className="muted">{r.source}</td>
                  <td>
                    <Tag tone={r.severity === "CRITICAL" ? "bad" : r.severity === "HIGH" ? "warn" : "neutral"}>{r.severity}</Tag>
                  </td>
                  {CHANNELS.map((c, j) => (
                    <td key={c} className="center">
                      <input
                        type="checkbox"
                        className="matrix-check"
                        checked={matrix[i][j]}
                        aria-label={`${r.alert} by ${c}`}
                        onChange={() => setMatrix((m) => m.map((row, ri) => (ri === i ? row.map((v, ci) => (ci === j ? !v : v)) : row)))}
                      />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
