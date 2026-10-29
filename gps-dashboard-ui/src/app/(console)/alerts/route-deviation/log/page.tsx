"use client";

import { History } from "@/components/icons";
import { Card, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader, PlannedNote } from "@/components/ops/module";
import { DEVIATIONS } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const ROUTE = moduleById("route")!;

export default function DeviationLogPage() {
  const open = DEVIATIONS.filter((d) => d.state === "OPEN").length;
  return (
    <div className="stack">
      <ModuleHeader module={ROUTE} title="Deviation log" description="Every time a vehicle left its corridor: how far, how long, and whether it came back." />
      <PlannedNote what="The deviation log" />

      <Stats columns={3}>
        <Tile label="Off route now" value={open} tone={open ? "bad" : undefined} big />
        <Tile label="Deviations today" value={DEVIATIONS.length} big />
        <Tile label="Rejoined" value={DEVIATIONS.length - open} tone="good" big />
      </Stats>

      <Card icon={History} title="Deviations" subtitle="Newest first." live={false} flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Left at</th>
                <th>Vehicle</th>
                <th>Corridor</th>
                <th className="right">Furthest off</th>
                <th className="right">Off route for</th>
                <th>Severity</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {DEVIATIONS.map((d) => (
                <tr key={`${d.time}-${d.vehicle}`} className={d.state === "OPEN" ? "flagged" : undefined}>
                  <td className="mono">{d.time}</td>
                  <td className="mono">{d.vehicle}</td>
                  <td>{d.corridor}</td>
                  <td className="right mono">{d.offBy}</td>
                  <td className="right mono">{d.duration}</td>
                  <td>
                    <Tag tone={d.severity === "CRITICAL" ? "bad" : d.severity === "HIGH" ? "warn" : "neutral"}>{d.severity}</Tag>
                  </td>
                  <td>{d.state === "OPEN" ? <Tag tone="ink">OFF ROUTE</Tag> : <span className="faint">rejoined</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
