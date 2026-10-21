"use client";

import { useState } from "react";
import { ListChecks } from "lucide-react";
import { EventTable } from "@/components/ops/events";
import { Card, Segmented } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const SPEED = moduleById("speed")!;
type Kind = "OVERSPEED,SPEED_SENSOR_MISMATCH" | "OVERSPEED" | "SPEED_SENSOR_MISMATCH";
type Status = "" | "OPEN" | "CLOSED";

export default function SpeedEventsPage() {
  const [kind, setKind] = useState<Kind>("OVERSPEED,SPEED_SENSOR_MISMATCH");
  const [status, setStatus] = useState<Status>("");
  const events = usePoll<VehicleEventView[]>(`/api/events?type=${kind}&limit=60${status ? `&status=${status}` : ""}`, 4000);

  return (
    <div className="stack">
      <ModuleHeader module={SPEED} title="Speed events" description="Over-speed and sensor-mismatch events raised by this processor, published on gps.vehicle-events and stored in vehicle_event." />

      <Card
        icon={ListChecks}
        title="Events"
        subtitle="Newest first."
        live
        flush
        actions={
          <div className="toolbar-row">
            <Segmented<Kind>
              label="Event type"
              value={kind}
              onChange={setKind}
              options={[
                ["OVERSPEED,SPEED_SENSOR_MISMATCH", "Both"],
                ["OVERSPEED", "Over-speed"],
                ["SPEED_SENSOR_MISMATCH", "Mismatch"],
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
        <EventTable events={events.data} empty="No speed events yet: with one ping a minute a truck needs 3 pings in a row over the limit." />
      </Card>
    </div>
  );
}
