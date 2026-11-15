"use client";

import Link from "next/link";
import { useState } from "react";
import { Route, Server } from "@/components/icons";
import { Callout, Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader, SettingsCard } from "@/components/ops/module";
import { EtaStrips } from "@/components/routes/eta-strip";
import { moduleById } from "@/lib/modules";
import { minutesText, type Engines, type LiveRoutes, type Trip } from "@/lib/routes";
import { usePoll } from "@/lib/use-poll";

const ROUTE = moduleById("route")!;
type Show = "ACTIVE" | "PROBLEMS" | "LATE" | "ARRIVED" | "ALL";

function matches(t: Trip, show: Show): boolean {
  if (show === "ALL") return true;
  if (show === "ARRIVED") return t.routeStatus === "ARRIVED";
  if (show === "LATE") return (t.lateMinutes ?? 0) >= 30;
  if (show === "PROBLEMS") return t.routeStatus === "OFF_ROUTE" || t.routeStatus === "SUSPECT" || t.wrongWay || (t.noSignal && t.status === "ACTIVE");
  return t.status === "ACTIVE";
}

/** Every planned trip as a live strip: where the truck is along its road route, its state and its ETA. */
export default function LiveTripsPage() {
  const [show, setShow] = useState<Show>("ACTIVE");
  const { data, error } = usePoll<LiveRoutes>("/api/routes/live?hours=24", 5_000);
  const { data: engines } = usePoll<Engines>("/api/routes/engines", 60_000);
  const trips = (data?.trips ?? []).filter((t) => matches(t, show));
  const c = data?.counts;

  return (
    <div className="stack">
      <ModuleHeader
        module={ROUTE}
        title="Live trips & ETA"
        description="Every trip with a planned road route - each shipment from its plant to its customer, and routes planned by hand. The truck is drawn at its progress along the plan; green on route, amber while a detour is checked, red off route or driving the wrong way. The ETA uses the truck's own pace and is compared with the one promised when it left."
        actions={
          <Link className="btn" href="/alerts/route-deviation/plan">
            Plan a route
          </Link>
        }
      />

      {error && !data && <Callout tone="warn" title="Trips did not load">{error}</Callout>}

      <Stats columns={5}>
        <Tile label="On route" value={c?.ON_ROUTE ?? 0} tone="good" note={`${c?.WAITING ?? 0} not left yet`} big />
        <Tile label="Off route now" value={(c?.OFF_ROUTE ?? 0) + (c?.SUSPECT ?? 0)} tone={c?.OFF_ROUTE ? "bad" : undefined} note={`${c?.SUSPECT ?? 0} being checked`} big />
        <Tile label="Wrong direction" value={c?.WRONG_WAY ?? 0} tone={c?.WRONG_WAY ? "bad" : undefined} big />
        <Tile label="Late by 30 min +" value={c?.LATE ?? 0} tone={c?.LATE ? "warn" : undefined} note={data?.averageLateMinutes != null ? `average ${data.averageLateMinutes > 0 ? "+" : ""}${minutesText(data.averageLateMinutes)}` : undefined} big />
        <Tile label="Arrived (24 h)" value={c?.ARRIVED ?? 0} tone="good" note={`${c?.NO_SIGNAL ?? 0} silent 30 min +`} big />
      </Stats>

      <Card
        icon={Route}
        title="Trips"
        subtitle="Open one for its map: planned road, the real trail on and off it, stops and events."
        live={!error}
        actions={
          <Segmented<Show>
            label="Show"
            value={show}
            onChange={setShow}
            options={[
              ["ACTIVE", "On the road"],
              ["PROBLEMS", "Needs attention"],
              ["LATE", "Late"],
              ["ARRIVED", "Arrived"],
              ["ALL", "All 24 h"],
            ]}
          />
        }
      >
        {trips.length ? (
          <EtaStrips trips={trips} hrefOf={(t) => `/alerts/route-deviation/trip?id=${t.planId}`} />
        ) : (
          <Empty>
            {data?.trips.length
              ? "No trip matches this filter."
              : "No planned trips yet. Every open shipment with an origin and a destination geofence is planned within 30 s (TMS → Shipments, or Load demo shipments), or plan one by hand."}
          </Empty>
        )}
      </Card>

      <div className="grid-main-side">
        <HowItWorks module={ROUTE} />
        <Card icon={Server} title="Routing engines" subtitle="Valhalla first; GraphHopper when the HP does not answer." live={!!engines}>
          <div className="stack">
            {engines &&
              (["valhalla", "graphhopper"] as const).map((k) => (
                <div key={k} className="toolbar-row">
                  <strong style={{ minWidth: 100 }}>{k === "valhalla" ? "Valhalla" : "GraphHopper"}</strong>
                  <Tag tone={engines[k].up ? "good" : "bad"}>{engines[k].up ? "UP" : "DOWN"}</Tag>
                  <span className="faint mono">{engines[k].url ?? "not set"}</span>
                  <span className="faint">
                    {engines[k].up ? `${engines[k].version ? `v${engines[k].version} · ` : ""}${engines[k].millis ?? "?"} ms` : engines[k].problem}
                  </span>
                </div>
              ))}
            {engines && <span className="faint">Costing: {engines.costing}. Change Valhalla's address on Developer → Connections.</span>}
          </div>
        </Card>
      </div>

      <SettingsCard
        title="Deviation and ETA settings"
        subtitle="gps.modules.route-deviation.* in application.yml"
        source="application.yml"
        live
        settings={[
          { label: "Corridor, highway", value: 150, unit: "m" },
          { label: "Corridor, town", value: 80, unit: "m" },
          { label: "Check after", value: "3 readings", hint: "and 5 min and 1 km driven off the line" },
          { label: "Or at once beyond", value: 2, unit: "km" },
          { label: "Alternate road if within", value: "10 %", hint: "or 15 min of the planned remaining time" },
          { label: "HIGH / CRITICAL after", value: "30 / 90", unit: "min added" },
          { label: "Wrong direction", value: 1, unit: "km back", hint: "over 3 moving readings" },
          { label: "Driver rest", value: "30 min", hint: "per 4.5 h of driving still ahead" },
          { label: "Truck's own pace trusted", value: "50 %", hint: "after 1 h of driving, more after that" },
          { label: "ETA_CHANGED after", value: 30, unit: "min", hint: "or 10 % of the time left" },
        ]}
      />
    </div>
  );
}
