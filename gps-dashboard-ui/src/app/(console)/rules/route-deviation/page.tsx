"use client";

import { Navigation, TriangleAlert, Waypoints } from "lucide-react";
import { Card, DemoButton, KeyValues, PageTitle, Tag } from "@/components/ops/kit";

export default function RouteDeviationPage() {
  return (
    <div className="stack">
      <PageTitle
        title="Route deviation"
        subtitle="Each planned route is a polyline with a buffer. Leaving the buffer starts a detour timer; a long detour becomes a critical alert, and the system works out where the vehicle can rejoin."
      />

      <div className="grid-2">
        <Card
          icon={Waypoints}
          title="Corridor guard"
          subtitle="BUFFER: ±50m · Polyline buffer"
          monoSubtitle
          badges={<Tag tone="bad">1 BREACH</Tag>}
          live={false}
        >
          <svg className="geo" viewBox="0 0 1000 200" preserveAspectRatio="none" role="img" aria-label="Planned corridor with a vehicle outside it">
            <path className="outer" d="M 0 120 C 250 60, 450 160, 1000 90 L 1000 130 C 450 200, 250 100, 0 160 Z" />
            <path className="track" d="M 0 140 C 250 80, 420 180, 600 150 L 700 60" />
            <circle className="vehicle" cx="700" cy="60" r="9" />
            <text x="610" y="40">DEV: +180m off-corridor</text>
          </svg>
          <div className="inner" style={{ marginTop: 16, background: "var(--bad-soft)" }}>
            <div style={{ display: "flex", justifyContent: "space-between", gap: 12 }}>
              <strong className="bad">
                <TriangleAlert size={15} style={{ verticalAlign: -2, marginRight: 6 }} />
                Unauthorised detour
              </strong>
              <Tag tone="bad">CRITICAL</Tag>
            </div>
            <p className="muted" style={{ marginTop: 4, fontSize: 13 }}>
              Time off-route: 14 min 20 s
            </p>
          </div>
          <div className="card-actions">
            <DemoButton>Configure polygon buffers</DemoButton>
          </div>
        </Card>

        <Card icon={Navigation} title="Smart ingress recalculation" subtitle="How the vehicle gets back on its route." live={false}>
          <KeyValues
            rows={[
              ["Return vector", "Computed via exit 42B"],
              ["Rejoins route", <span className="mono" key="r">in 1.4 km</span>],
              ["Extra distance", <span className="mono" key="e">+3.2 km</span>],
              ["Extra time", <span className="mono" key="t">+6 min</span>],
              ["Driver notified", <Tag tone="good" key="n">ACK</Tag>],
            ]}
          />
          <div className="card-foot">
            <span>Deviation tolerance</span>
            <span className="mono">Average 2.4 min · threshold 5 min</span>
          </div>
        </Card>
      </div>
    </div>
  );
}
