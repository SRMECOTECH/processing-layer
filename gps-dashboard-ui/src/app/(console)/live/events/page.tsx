"use client";

import { useState } from "react";
import { ListChecks } from "lucide-react";
import { EVENT_LABEL, EventTable } from "@/components/ops/events";
import { Card, PageHeader, Segmented, Stats, Tile } from "@/components/ops/kit";
import type { EventTypeCount, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Kind = "ALL" | "SPEED" | "STOP" | "IGNITION" | "DRIVING";
type Status = "" | "OPEN" | "CLOSED";

const TYPES: Record<Kind, string> = {
  ALL: "",
  SPEED: "OVERSPEED,SPEED_SENSOR_MISMATCH",
  STOP: "STOPPAGE,PARKED,IDLING",
  IGNITION: "IGNITION_ON,IGNITION_OFF",
  DRIVING: "HARSH_ACCELERATION,HARSH_BRAKING",
};

export default function EventStreamPage() {
  const [kind, setKind] = useState<Kind>("ALL");
  const [status, setStatus] = useState<Status>("");
  const [limit, setLimit] = useState(50);
  const query = new URLSearchParams({ limit: String(limit) });
  if (TYPES[kind]) query.set("type", TYPES[kind]);
  if (status) query.set("status", status);
  const events = usePoll<VehicleEventView[]>(`/api/events?${query}`, 3000);
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const total24 = (summary.data ?? []).reduce((s, c) => s + c.last24Hours, 0);
  const open = (summary.data ?? []).reduce((s, c) => s + c.openNow, 0);
  const top = [...(summary.data ?? [])].sort((a, b) => b.last24Hours - a.last24Hours)[0];

  return (
    <div className="stack">
      <PageHeader
        title="Event stream"
        description="Every event the stream consumers raise (Kafka topic gps.vehicle-events, stored in vehicle_event), newest first. Refreshes every 3 seconds."
      />

      <Stats columns={3}>
        <Tile label="Events in 24 hours" value={summary.data ? total24 : undefined} big />
        <Tile label="Ongoing now" value={summary.data ? open : undefined} big />
        <Tile label="Most frequent" value={top ? (EVENT_LABEL[top.type as VehicleEventView["type"]] ?? top.type) : "–"} foot={top ? `${top.last24Hours} in 24 hours` : undefined} />
      </Stats>

      <Card
        icon={ListChecks}
        title="Events"
        subtitle={`Showing up to ${limit}`}
        live
        flush
        actions={
          <div className="toolbar-row">
            <Segmented<Kind>
              label="Event kind"
              value={kind}
              onChange={setKind}
              options={[
                ["ALL", "All"],
                ["SPEED", "Speed"],
                ["STOP", "Stops"],
                ["IGNITION", "Ignition"],
                ["DRIVING", "Driving"],
              ]}
            />
            <Segmented<Status>
              label="Status"
              value={status}
              onChange={setStatus}
              options={[
                ["", "Any"],
                ["OPEN", "Ongoing"],
                ["CLOSED", "Ended"],
              ]}
            />
          </div>
        }
      >
        <EventTable events={events.data} empty="No events match." />
        <div className="card-foot">
          <span>Click a vehicle to open it in the inspector.</span>
          {events.data && events.data.length >= limit && (
            <button type="button" className="btn soft" onClick={() => setLimit((l) => l + 50)}>
              Show 50 more
            </button>
          )}
        </div>
      </Card>
    </div>
  );
}
