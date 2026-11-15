"use client";

import { useMemo } from "react";
import { Callout, Card, Empty, PageHeader, Progress, Stats, Tile } from "@/components/ops/kit";
import { StatusTag } from "@/components/roads/module-card";
import { moduleByKey } from "@/lib/road-intelligence";
import { ROAD_CLASS_LABEL, classLabel, type KnownRoad } from "@/lib/roads";
import { usePoll } from "@/lib/use-poll";

const ONE = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 1 });
const HIGHWAY = new Set(["motorway", "trunk", "primary"]);

/** B. Road classification & hierarchy: the roads we know, by class and by town / open road. */
export default function RoadClassificationPage() {
  const m = moduleByKey("classification")!;
  const roads = usePoll<KnownRoad[]>("/api/roads?limit=2000", 60_000);

  const t = useMemo(() => {
    const byClass = new Map<string, { km: number; town: number; roads: number; withOsmLimit: number }>();
    let total = 0;
    let highway = 0;
    let town = 0;
    for (const r of roads.data ?? []) {
      const c = byClass.get(r.roadClass) ?? { km: 0, town: 0, roads: 0, withOsmLimit: 0 };
      c.km += r.km;
      c.roads += 1;
      if (r.area === "TOWN") c.town += r.km;
      if (r.limitSource === "OSM") c.withOsmLimit += r.km;
      byClass.set(r.roadClass, c);
      total += r.km;
      if (HIGHWAY.has(r.roadClass)) highway += r.km;
      if (r.area === "TOWN") town += r.km;
    }
    const order = Object.keys(ROAD_CLASS_LABEL);
    const rows = [...byClass.entries()].sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]));
    return { rows, total, highway, town };
  }, [roads.data]);

  return (
    <div className="stack">
      <PageHeader
        title="B. Road classification & hierarchy"
        description={m.summary}
        badges={<StatusTag status={m.status} />}
        meta={[
          ["Classes", "Valhalla road_class (from OpenStreetMap highway=*)"],
          ["Town", "Valhalla density at or above the town setting"],
        ]}
      />
      {!roads.data ? (
        <Empty>{roads.error ?? "Loading…"}</Empty>
      ) : (
        <>
          <Stats columns={4}>
            <Tile label="Road length known" value={t.total} format={(v) => ONE.format(v)} unit="km" />
            <Tile label="Highways" value={t.total ? (100 * t.highway) / t.total : null} format={(v) => ONE.format(v)} unit="%" note="expressway, NH, SH" />
            <Tile label="Local roads" value={t.total ? (100 * (t.total - t.highway)) / t.total : null} format={(v) => ONE.format(v)} unit="%" />
            <Tile label="In towns" value={t.town} format={(v) => ONE.format(v)} unit="km" tone={t.town === 0 ? "warn" : undefined} />
          </Stats>
          <Card title="Length by class" subtitle="Most important first: the hierarchy from expressway to service road." flush>
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Class</th>
                    <th>Length</th>
                    <th>Share</th>
                    <th>Roads</th>
                    <th>In towns</th>
                    <th>Limit from OpenStreetMap</th>
                  </tr>
                </thead>
                <tbody>
                  {t.rows.map(([c, v]) => (
                    <tr key={c}>
                      <td>
                        <strong>{classLabel(c)}</strong>
                      </td>
                      <td>{ONE.format(v.km)} km</td>
                      <td style={{ minWidth: 140 }}>
                        <Progress value={t.total ? (100 * v.km) / t.total : 0} /> {ONE.format(t.total ? (100 * v.km) / t.total : 0)} %
                      </td>
                      <td>{v.roads}</td>
                      <td>{v.town > 0 ? `${ONE.format(v.town)} km` : "–"}</td>
                      <td>{v.withOsmLimit > 0 ? `${ONE.format(v.withOsmLimit)} km` : "–"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
          {t.town === 0 && (
            <Callout tone="warn" title="No road counted as town">
              Valhalla&apos;s density rarely reaches the town setting (9) on Indian roads, so the town rows of the limit table do not apply yet.
              Lower it with GPS_ROADS_TOWN_DENSITY_FROM once a few known towns have been checked.
            </Callout>
          )}
          <Callout tone="planned" title="Next for classification">
            Road density by district and a classification heatmap need district boundaries and the whole road network (phase 2).
          </Callout>
        </>
      )}
    </div>
  );
}
