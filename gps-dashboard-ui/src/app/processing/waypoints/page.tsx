"use client";

import { MapPin, Route } from "lucide-react";
import { Card, DemoButton, Tag } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader, PlannedNote, SettingsCard } from "@/components/ops/module";
import { ROUTE_PLANS } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const WAYPOINTS = moduleById("waypoints")!;

const STATE_TEXT = { done: "Reached", late: "Reached late", next: "Next", pending: "Planned", missed: "Missed" };

export default function RoutesAndWaypointsPage() {
  return (
    <div className="stack">
      <ModuleHeader module={WAYPOINTS} title="Waypoints · routes & waypoints" actions={<DemoButton primary>New route plan</DemoButton>} />
      <PlannedNote what="Waypoint tracking" />

      {ROUTE_PLANS.map((plan) => (
        <Card
          key={plan.trip}
          icon={Route}
          title={plan.route}
          subtitle={`${plan.trip} · ${plan.vehicle}`}
          monoSubtitle
          live={false}
          badges={plan.stops.some((s) => s.state === "missed") ? <Tag tone="bad">WAYPOINT MISSED</Tag> : <Tag tone="good">ON PLAN</Tag>}
        >
          <ol className="waypoints">
            {plan.stops.map((stop, i) => (
              <li key={stop.name} className={`waypoint ${stop.state}`}>
                <span className="waypoint-dot">{stop.state === "done" || stop.state === "late" ? <MapPin size={13} /> : i + 1}</span>
                <span className="waypoint-name">{stop.name}</span>
                <span className="waypoint-time mono">
                  {stop.planned}
                  {stop.actual && <span className="faint"> · {stop.actual}</span>}
                </span>
                <span className="waypoint-state">{STATE_TEXT[stop.state]}</span>
              </li>
            ))}
          </ol>
        </Card>
      ))}

      <SettingsCard
        title="Waypoint settings"
        subtitle="Proposed defaults."
        source="planned"
        settings={[
          { label: "Arrival radius", value: 150, unit: "m" },
          { label: "Counts as visited after", value: 60, unit: "s inside" },
          { label: "Order", value: "strict", hint: "Out-of-order visits are flagged" },
          { label: "Late after", value: 10, unit: "min" },
          { label: "ETA speed", value: "Kalman", hint: "Smoothed speed over the last 10 min" },
        ]}
      />

      <HowItWorks module={WAYPOINTS} />
    </div>
  );
}
