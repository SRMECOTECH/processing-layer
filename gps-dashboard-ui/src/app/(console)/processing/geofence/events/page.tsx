"use client";

import { useState } from "react";
import { LogIn } from "lucide-react";
import { Card, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader, PlannedNote } from "@/components/ops/module";
import { GEOFENCE_EVENTS } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const GEOFENCE = moduleById("geofence")!;
type Kind = "ALL" | "ENTER" | "EXIT" | "DWELL";

export default function GeofenceEventsPage() {
  const [kind, setKind] = useState<Kind>("ALL");
  const rows = GEOFENCE_EVENTS.filter((e) => kind === "ALL" || e.type === kind);
  const count = (t: string) => GEOFENCE_EVENTS.filter((e) => e.type === t).length;

  return (
    <div className="stack">
      <ModuleHeader module={GEOFENCE} title="Entry & exit" description="Events raised when the deepest zone around a vehicle changes, and when a vehicle stays longer than a zone allows." />
      <PlannedNote what="Geofence events" />

      <Stats columns={3}>
        <Tile label="Entries (example)" value={count("ENTER")} big />
        <Tile label="Exits (example)" value={count("EXIT")} big />
        <Tile label="Dwell alerts (example)" value={count("DWELL")} tone="warn" big />
      </Stats>

      <Card
        icon={LogIn}
        title="Events"
        subtitle="Would be published on gps.vehicle-events."
        live={false}
        flush
        actions={
          <Segmented<Kind>
            label="Event type"
            value={kind}
            onChange={setKind}
            options={[
              ["ALL", "All"],
              ["ENTER", "Enter"],
              ["EXIT", "Exit"],
              ["DWELL", "Dwell"],
            ]}
          />
        }
      >
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Vehicle</th>
                <th>Event</th>
                <th>Zone</th>
                <th>Level</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={`${e.time}-${e.vehicle}`}>
                  <td className="mono">{e.time}</td>
                  <td className="mono">{e.vehicle}</td>
                  <td>
                    <Tag tone={e.type === "ENTER" ? "good" : e.type === "EXIT" ? "neutral" : "warn"}>{e.type}</Tag>
                  </td>
                  <td>{e.zone}</td>
                  <td className="muted">{e.level}</td>
                  <td className="muted">{e.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
