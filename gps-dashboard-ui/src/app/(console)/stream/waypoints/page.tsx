"use client";

import { MapPin, Waypoints } from "lucide-react";
import { Card, DemoButton, PageTitle, Progress, Tag } from "@/components/ops/kit";

const CORRIDORS: [string, string, number][] = [
  ["Corridor #401", "Dallas Hub", 0.998],
  ["Corridor #109", "Austin Express", 1],
  ["Corridor #882", "Houston Port", 0.984],
  ["Corridor #217", "Fort Worth Ring", 0.962],
];

const WAYPOINTS: [string, string, string, string][] = [
  ["TRIP-7029-US", "Depot North", "Reached 14:02", "On time"],
  ["TRIP-7029-US", "Exit 42B", "Expected 14:31", "2 min late"],
  ["TRIP-7031-US", "Port Gate 3", "Reached 13:55", "On time"],
  ["TRIP-7034-US", "Cold Storage Yard", "Expected 15:10", "On time"],
];

export default function WaypointsPage() {
  return (
    <div className="stack">
      <PageTitle
        title="Waypoints and corridors"
        subtitle="Planned routes as corridors with a buffer, and the waypoints each trip must pass. Lock shows how much of the time vehicles stay inside their corridor."
      />

      <Card icon={Waypoints} title="Active geo-corridors" subtitle="Live telemetry node density and waypoint mesh" live={false}>
        <div className="grid-2">
          {CORRIDORS.map(([name, place, lock]) => (
            <div className="inner" key={name}>
              <div style={{ display: "flex", justifyContent: "space-between", gap: 12 }}>
                <span className="mono">
                  <span className="dot" />
                  {name} ({place})
                </span>
                <span className="mono accent-text">{(lock * 100).toFixed(1)}% lock</span>
              </div>
              <Progress value={lock} />
            </div>
          ))}
        </div>
        <div className="card-foot">
          <span>Sector 04, North Texas · 1,412 fleet transponders</span>
          <DemoButton>Inspect corridor</DemoButton>
        </div>
      </Card>

      <Card icon={MapPin} title="Waypoints" subtitle="Checkpoints along active trips." live={false}>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Trip</th>
                <th>Waypoint</th>
                <th>Time</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {WAYPOINTS.map(([trip, point, time, status]) => (
                <tr key={`${trip}-${point}`}>
                  <td className="mono">{trip}</td>
                  <td>{point}</td>
                  <td className="muted">{time}</td>
                  <td>
                    <Tag tone={status === "On time" ? "good" : "warn"}>{status.toUpperCase()}</Tag>
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
