"use client";

import { Navigation, Waypoints } from "lucide-react";
import { Card, DemoButton, KeyValues, Progress, Tag } from "@/components/ops/kit";
import { ModuleHeader, PlannedNote, SettingsCard } from "@/components/ops/module";
import { CORRIDORS } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const ROUTE = moduleById("route")!;

export default function CorridorsPage() {
  return (
    <div className="stack">
      <ModuleHeader
        module={ROUTE}
        title="Route deviation · corridors"
        description="Each planned route becomes a corridor: the route line with a buffer on both sides. Leaving the corridor starts a detour timer; staying out longer than the tolerance becomes an alert, and the system works out where the vehicle can rejoin."
        actions={<DemoButton primary>New corridor</DemoButton>}
      />
      <PlannedNote what="Route deviation" />

      <div className="grid-main-side">
        <Card icon={Waypoints} title="Corridor guard" subtitle="Buffer ±250 m around the planned route" live={false} badges={<Tag tone="bad">1 OFF ROUTE</Tag>}>
          <svg className="geo" viewBox="0 0 1000 260" preserveAspectRatio="none" role="img" aria-label="Planned corridor with a vehicle outside it">
            <path className="corridor" d="M 0 150 C 250 90, 450 190, 1000 120 L 1000 170 C 450 240, 250 140, 0 200 Z" />
            <path className="route-line" d="M 0 175 C 250 115, 450 215, 1000 145" />
            <path className="track" d="M 0 178 C 240 118, 420 210, 600 182 L 700 80" />
            <circle className="vehicle" cx="700" cy="80" r="9" />
            <text x="640" y="52">off by 1.8 km for 14 min</text>
          </svg>
          <div className="corridor-list">
            {CORRIDORS.map((c) => (
              <div className="corridor-row" key={c.name}>
                <div className="corridor-name">
                  <strong>{c.name}</strong>
                  <span className="faint">
                    {c.route} · ±{c.buffer} m
                  </span>
                </div>
                <div className="corridor-stat">
                  <Progress value={c.inside} tone={c.inside < 0.97 ? "warn" : undefined} />
                  <span className="mono">{(c.inside * 100).toFixed(1)} % inside</span>
                </div>
                <Tag tone={c.deviations ? "warn" : "good"}>{c.deviations} deviations</Tag>
              </div>
            ))}
          </div>
        </Card>

        <Card icon={Navigation} title="Getting back on route" subtitle="For the open deviation." live={false}>
          <KeyValues
            rows={[
              ["Vehicle", <span className="mono" key="v">JH05AK4471</span>],
              ["Rejoins route", <span className="mono" key="r">in 1.4 km</span>],
              ["Extra distance", <span className="mono" key="e">+3.2 km</span>],
              ["Extra time", <span className="mono" key="t">+6 min</span>],
              ["Driver notified", <Tag tone="good" key="n">ACKNOWLEDGED</Tag>],
            ]}
          />
        </Card>
      </div>

      <SettingsCard
        title="Deviation settings"
        subtitle="Proposed defaults, overridable per corridor."
        source="planned"
        settings={[
          { label: "Corridor buffer", value: 250, unit: "m" },
          { label: "Alert after off-route for", value: 5, unit: "min" },
          { label: "Or further than", value: 1, unit: "km" },
          { label: "Confirm exit after", value: 2, unit: "readings" },
          { label: "Critical after", value: 15, unit: "min" },
          { label: "Route source", value: "trip master", hint: "Planned route attached at trip start" },
        ]}
      />
    </div>
  );
}
