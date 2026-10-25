"use client";

import { DatabaseZap, Play } from "lucide-react";
import { Card, KeyValues, PageTitle, Tag } from "@/components/ops/kit";
import { EmptyDbButton } from "@/components/ops/empty-db";
import { FleetButton } from "@/components/simulator-control";
import { formatNumber } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";

export default function DemoToolsPage() {
  const { data } = useOverview();
  const sim = data?.simulator;
  return (
    <div className="stack">
      <PageTitle
        title="Demo tools"
        subtitle="For practising the demo: empty everything, start the fleet, watch the consumers fill the platform again, and cross-check. Repeat as often as needed."
      />

      <Card icon={DatabaseZap} title="Empty DB" subtitle="POST /api/admin/reset" monoSubtitle live actions={<EmptyDbButton />}>
        <KeyValues
          rows={[
            ["Kafka", "Every consumer paused and moved to the end of its topic; every message in the four topics deleted"],
            ["PostgreSQL", "gps_position, gps_trip, gps_stop, gps_trip_late_reading, vehicle_motion_state, vehicle_event emptied"],
            ["Redis", "Live positions, vehicle states, history streams, events, consumer states and engine counters deleted"],
            ["Memory", "The processing step's previous readings and the load counters start from zero"],
            ["Kept", "Companies, vehicles, devices, assignments, and the speed rules (gps:config:*)"],
          ]}
        />
      </Card>

      <Card
        icon={Play}
        title="Simulated fleet"
        subtitle={sim?.lastMessage ?? "Connecting"}
        live
        badges={<Tag tone={sim?.running ? "good" : "neutral"}>{sim?.running ? "RUNNING" : "STOPPED"}</Tag>}
        actions={<FleetButton />}
      >
        <KeyValues
          rows={[
            ["Vehicles", formatNumber(sim?.vehicleCount)],
            ["Frames sent", formatNumber(sim?.framesSent)],
            ["Drive", sim ? `${sim.driveNumber} (forwards, then back, and so on)` : "–"],
            ["Timing", "Real gaps between pings; 2 hours of catch-up at 30x, then real time"],
            ["Devices", "Half report ignition, half an odometer; a quarter of the drivers leave the engine running when they stop"],
          ]}
        />
      </Card>
    </div>
  );
}
