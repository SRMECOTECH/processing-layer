"use client";

import { Callout, Card, PageHeader, Stats, Tile } from "@/components/ops/kit";
import { ModuleTile, StatusTag } from "@/components/roads/module-card";
import { Map as MapIcon, Route } from "@/components/icons";
import { ROAD_MODULES, VALHALLA_PROBE } from "@/lib/road-intelligence";
import type { RoadStatus } from "@/lib/roads";
import { usePoll } from "@/lib/use-poll";

/** Road Intelligence: what we know about every road our trucks use, and how it helps the operation. */
export default function RoadIntelligencePage() {
  const status = usePoll<RoadStatus>("/api/roads/status", 10_000);
  const s = status.data;
  const built = ROAD_MODULES.filter((m) => m.status === "live").length;
  const partial = ROAD_MODULES.filter((m) => m.status === "partial").length;

  return (
    <div className="stack">
      <PageHeader
        title="Road Intelligence"
        description="What we know about every road our trucks drive - names, class, limits, restrictions, geometry, infrastructure, risk - and how it helps transport operations. Separate from vehicle, driver and ETA intelligence. Roads are learned once with Valhalla's map matching and kept in PostgreSQL (road_segment); Valhalla stays for routing, matching and graph questions, never for every page view."
        meta={[
          ["Valhalla", s?.valhallaUrl ?? "…"],
          ["Library", "road_segment · road_speed_limit · vehicle_day_road"],
          ["Guide", "docs/ROAD_INTELLIGENCE.md"],
        ]}
      />
      <Stats columns={4}>
        <Tile label="Road pieces known" icon={Route} value={s?.roads ?? null} />
        <Tile label="Road length known" icon={MapIcon} value={s?.kilometres ?? null} unit="km" />
        <Tile label="Sub-modules built" value={built} note={`${partial} partly`} />
        <Tile label="Sub-modules planned" value={ROAD_MODULES.length - built - partial} />
      </Stats>

      <Callout tone="info" title="The roads we know are the roads trucks drove">
        Valhalla has no call that lists every road in India. The library holds what trips were map-matched onto (the test trip: 1,309 km).
        Network-wide questions - connectivity, bottlenecks, density by district - need the OpenStreetMap extract itself in PostGIS (phase 2).
      </Callout>

      <div className="grid-3">
        {ROAD_MODULES.map((m) => (
          <ModuleTile key={m.key} m={m} />
        ))}
      </div>

      <Card title="What this Valhalla actually returns" subtitle="Checked on the HP home server against the first 96 km of trip 28860462 (2026-11-15)." flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Attribute</th>
                <th>Result</th>
                <th>Usable</th>
              </tr>
            </thead>
            <tbody>
              {VALHALLA_PROBE.map((p) => (
                <tr key={p.attribute}>
                  <td>{p.attribute}</td>
                  <td className="muted">{p.result}</td>
                  <td>
                    <StatusTag status={p.usable} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
