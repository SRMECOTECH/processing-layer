"use client";

import { useState } from "react";
import { Crosshair, Grid3x3, ShieldCheck, Sigma } from "@/components/icons";
import { Callout, Card, KeyValues, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { ROLE_LABEL, type FenceRole, type GeofenceEngine } from "@/lib/geofence";
import { moduleById } from "@/lib/modules";
import { usePoll } from "@/lib/use-poll";

const GEOFENCE = moduleById("geofence")!;

type Located = { id: number; code: string; name: string; role: FenceRole; metersFromCentre: number };

/** How the module decides "inside", how fast it is, and how well it agrees with smart_truck. */
export default function GeofenceIndexPage() {
  const engine = usePoll<GeofenceEngine>("/api/geofences/engine", 60_000);
  const i = engine.data?.index;

  return (
    <div className="stack">
      <ModuleHeader
        module={GEOFENCE}
        title="Index & accuracy"
        description="Why a reading costs microseconds: India is cut into grid cells and every fence is filed in every cell it touches, so a reading looks in exactly one cell. Then an exact test: flat-earth distance for circles, ray casting for polygons."
      />

      <Stats columns={4}>
        <Tile label="Fences in the index" icon={Grid3x3} value={i?.fences} big />
        <Tile label="Filled cells" value={i?.filledCells} note={i ? `${i.cellDegrees}° ≈ ${i.cellKmApprox} km square` : "…"} big />
        <Tile label="Fences per filled cell" value={i ? i.averageFencesPerFilledCell : undefined} note={i ? `at most ${i.maxFencesPerCell}` : "…"} big />
        <Tile label="Cost per reading" value="0.5 µs" note="measured on 3.48 million smart_truck pings" big />
      </Stats>

      <div className="grid-2">
        <Card icon={Sigma} title="The algorithm, per reading">
          <pre className="code">{`cell      = (floor(lat / ${i?.cellDegrees ?? 0.05}), floor(lon / ${i?.cellDegrees ?? 0.05}))
candidates = grid[cell]              # usually empty: done
inside    = [f for f in candidates if f.contains(lat, lon)]

circle:   dy = Δlat · 111,320 m
          dx = Δlon · 111,320 m · cos(centre lat)
          inside when dx² + dy² ≤ r²   (no sqrt, no trig)
polygon:  ray to the east, count border crossings
          (yi > y) != (yj > y)  -> odd = inside

entered = inside − remembered      (held 15 min)
exited  = remembered − inside      (held 15 min, or > 2 radii out)`}</pre>
        </Card>
        <Card icon={ShieldCheck} title="Checked against smart_truck" subtitle="2026-11-02: every trip with geofence events replayed through this module's tracker, compared with tta_trip_geofence_events.">
          <KeyValues
            rows={[
              ["Trips · pings replayed", "2,220 · 3,483,057"],
              ["smart_truck enter/exit events found again", <Tag key="a" tone="good">95.6 %</Tag>],
              ["Of ours, confirmed by smart_truck", <Tag key="b" tone="good">98.8 %</Tag>],
              ["Matched at the exact same second", "15,848 of 16,126"],
              ["Speed, one thread", "0.51 µs per reading ≈ 1.9 million readings/s"],
              ["Differences", "Trip edges: smart_truck takes a trip's first ping as already inside; live, a visit is believed only after 15 minutes"],
            ]}
          />
        </Card>
      </div>

      <LocateCard />

      <Card icon={Grid3x3} title="Why one cell and not 'the k nearest cells'">
        <ul className="plain-list">
          <li>A 20 km fence (Chennai, Faridabad) spreads over dozens of 5.5 km cells. With k nearest cells, a reading in its corner cell, three cells from the centre, would miss it unless k were chosen from the largest fence.</li>
          <li>Filing a fence into every cell its bounding box touches costs a few hundred map entries, once, when the fences load. After that no fence is ever missed and nothing is sorted by distance.</li>
          <li>Cells follow the median fence size (never below 0.05°), not the largest, so the many 1 km customer fences are not piled into a few huge cells.</li>
          <li>Nested fences (Adityapur inside Jamshedpur, Gamharia-TSG inside Gamharia) come out together: a truck can be inside both, and both visits are kept.</li>
        </ul>
      </Card>
    </div>
  );
}

function LocateCard() {
  const [lat, setLat] = useState("22.7965");
  const [lon, setLon] = useState("86.1380");
  const [asked, setAsked] = useState<string | null>(null);
  const result = usePoll<Located[]>(asked, 600_000);

  return (
    <Card icon={Crosshair} title="Try a point" subtitle="Which fences is this position inside? The same lookup every reading goes through.">
      <div className="toolbar-row">
        <input className="input-box mono" value={lat} onChange={(e) => setLat(e.target.value)} aria-label="Latitude" />
        <input className="input-box mono" value={lon} onChange={(e) => setLon(e.target.value)} aria-label="Longitude" />
        <button type="button" className="btn primary" onClick={() => setAsked(`/api/geofences/locate?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`)}>
          Locate
        </button>
      </div>
      {result.error && <Callout tone="warn">{result.error}</Callout>}
      {result.data && (
        <p>
          {result.data.length === 0
            ? "Outside every fence."
            : result.data.map((f) => `${f.name} (${ROLE_LABEL[f.role] ?? f.role}, ${(f.metersFromCentre / 1000).toFixed(2)} km from its centre)`).join(" · ")}
        </p>
      )}
    </Card>
  );
}
