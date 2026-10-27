"use client";

import { useState } from "react";
import { ListChecks } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Callout, Card, Segmented } from "@/components/ops/kit";
import { ModuleHeader, SettingsCard } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const STOPPAGE = moduleById("stoppage")!;
type Kind = "STOPPAGE" | "IDLING" | "IGNITION_ON";

export default function IgnitionOnPage() {
  const [kind, setKind] = useState<Kind>("STOPPAGE");
  const events = usePoll<VehicleEventView[]>(`/api/events?type=${kind}&limit=40`, 4000);

  return (
    <div className="stack">
      <ModuleHeader
        module={STOPPAGE}
        title="Ignition on · rules"
        description="With the engine running, standing still has to be explained. The rules decide when a halt becomes a stoppage, using the state kept for the vehicle (its last pings, anchor point and how long it has been there)."
      />

      <Callout title="Real-time speed, not just the speedometer">
        Every ping's speed is also worked out as the distance travelled since the previous ping divided by the time between them. A vehicle counts
        as standing only when both say it is under the threshold, so a stuck speedometer or GPS drift cannot fake a stop.
      </Callout>

      <div className="grid-2">
        <SettingsCard
          title="Stoppage thresholds"
          subtitle="Stay-point detection."
          source="application.yml · gps.stream.stoppage"
          settings={[
            { label: "Standing below", value: 3, unit: "km/h", hint: "By speedometer and by distance ÷ time" },
            { label: "Within radius", value: 100, unit: "m", hint: "Must exceed GPS noise" },
            { label: "For at least", value: 180, unit: "s", hint: "3–4 readings at one a minute" },
            { label: "Leaves after", value: 2, unit: "readings", hint: "Outside the radius, in a row" },
            { label: "Silence ends a candidate", value: 1800, unit: "s" },
            { label: "Merge stops closer than", value: 180, unit: "s", hint: "Same place, short hop" },
          ]}
        />
        <SettingsCard
          title="Ignition and idling"
          subtitle="Engine state per vehicle."
          source="application.yml · gps.stream.ignition"
          settings={[
            { label: "Idle below", value: 3, unit: "km/h" },
            { label: "Idling alert after", value: 300, unit: "s" },
            { label: "Virtual ignition off after", value: 300, unit: "s", hint: "No wire: standing this long" },
            { label: "Virtual debounce", value: 2, unit: "readings" },
          ]}
        />
      </div>

      <Card
        icon={ListChecks}
        title="Events with the engine on"
        subtitle="From gps-stoppage-service and gps-ignition-service."
        live
        flush
        actions={
          <Segmented<Kind>
            label="Event type"
            value={kind}
            onChange={setKind}
            options={[
              ["STOPPAGE", "Stoppages"],
              ["IDLING", "Idling"],
              ["IGNITION_ON", "Ignition on"],
            ]}
          />
        }
      >
        <EventTable events={events.data} showType={false} />
      </Card>
    </div>
  );
}
