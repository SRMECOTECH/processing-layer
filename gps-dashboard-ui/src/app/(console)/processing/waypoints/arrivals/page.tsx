"use client";

import { Clock } from "@/components/icons";
import { Card, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader, PlannedNote } from "@/components/ops/module";
import { ARRIVALS } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const WAYPOINTS = moduleById("waypoints")!;
const TONE = { "ON TIME": "good", EARLY: "accent", LATE: "warn", MISSED: "bad", "EN ROUTE": "neutral" } as const;

export default function ArrivalsPage() {
  const count = (s: string) => ARRIVALS.filter((a) => a.status === s).length;
  return (
    <div className="stack">
      <ModuleHeader module={WAYPOINTS} title="Arrivals & ETA" description="Planned against actual times at every waypoint, and the expected arrival at the next one." />
      <PlannedNote what="Arrival tracking" />

      <Stats>
        <Tile label="On time or early" value={count("ON TIME") + count("EARLY")} tone="good" big />
        <Tile label="Late" value={count("LATE")} tone="warn" big />
        <Tile label="Missed" value={count("MISSED")} tone="bad" big />
        <Tile label="En route" value={count("EN ROUTE")} big />
      </Stats>

      <Card icon={Clock} title="Waypoint arrivals" subtitle="Example data." live={false} flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Trip</th>
                <th>Vehicle</th>
                <th>Waypoint</th>
                <th className="right">Planned</th>
                <th className="right">Actual</th>
                <th className="right">ETA</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {ARRIVALS.map((a) => (
                <tr key={`${a.trip}-${a.waypoint}`} className={a.status === "MISSED" ? "flagged" : undefined}>
                  <td className="mono">{a.trip}</td>
                  <td className="mono">{a.vehicle}</td>
                  <td>{a.waypoint}</td>
                  <td className="right mono">{a.planned}</td>
                  <td className="right mono">{a.actual}</td>
                  <td className="right mono">{a.eta}</td>
                  <td>
                    <Tag tone={TONE[a.status]}>{a.status}</Tag>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
