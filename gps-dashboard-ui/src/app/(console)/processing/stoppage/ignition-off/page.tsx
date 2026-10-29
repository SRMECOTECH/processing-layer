"use client";

import { useState } from "react";
import { ParkingSquare } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Callout, Card, Segmented, Stats, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import type { EventTypeCount, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const STOPPAGE = moduleById("stoppage")!;
type Kind = "PARKED" | "IGNITION_OFF";

export default function IgnitionOffPage() {
  const [kind, setKind] = useState<Kind>("PARKED");
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const events = usePoll<VehicleEventView[]>(`/api/events?type=${kind}&limit=40`, 4000);
  const n = (type: string, f: "openNow" | "last24Hours") => summary.data?.find((s) => s.type === type)?.[f] ?? (summary.data ? 0 : undefined);

  return (
    <div className="stack">
      <ModuleHeader
        module={STOPPAGE}
        title="Ignition off · neutralised"
        description="With the engine off the vehicle is parked. There is no point raising a stoppage, so its points are neutralised: recorded as parking, kept out of stoppage alarms, and the GPS jitter while parked adds no distance."
      />

      <Stats columns={3}>
        <Tile label="Parked now" value={n("PARKED", "openNow")} big />
        <Tile label="Parking periods (24 h)" value={n("PARKED", "last24Hours")} big />
        <Tile label="Ignition off (24 h)" value={n("IGNITION_OFF", "last24Hours")} big />
      </Stats>

      <Callout title="What neutralised means">
        The stoppage detector still tracks the stay point (so a later departure is recognised), but it classifies it as PARKED instead of
        STOPPAGE, raises no alarm and skips the traffic and weather agents. Distance stays clamped to the parking position.
      </Callout>

      <Card
        icon={ParkingSquare}
        title="Parking and ignition-off events"
        subtitle="Engine off."
        live
        flush
        actions={
          <Segmented<Kind>
            label="Event type"
            value={kind}
            onChange={setKind}
            options={[
              ["PARKED", "Parked"],
              ["IGNITION_OFF", "Ignition off"],
            ]}
          />
        }
      >
        <EventTable events={events.data} showType={false} />
      </Card>
    </div>
  );
}
