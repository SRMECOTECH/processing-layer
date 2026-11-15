"use client";

import { Card, Empty, Stats, Tile } from "@/components/ops/kit";
import { classLabel, type RoadAttributes } from "@/lib/roads";
import { usePoll } from "@/lib/use-poll";

/** What Valhalla's map matching told us about the learned roads (road_segment details, V13), in totals and lists. */
export function RoadAttributesSection() {
  const poll = usePoll<RoadAttributes>("/api/roads/attributes", 30_000);
  const a = poll.data;
  if (!a) {
    return (
      <Card title="What Valhalla tells us about these roads">
        <Empty>{poll.error ?? "Loading…"}</Empty>
      </Card>
    );
  }
  const t = a.totals;
  const directionKm = t.oneWayKm + t.twoWayKm;
  const readingShare = a.readings.readings ? a.readings.withRoad / a.readings.readings : null;

  return (
    <>
      <Stats columns={4}>
        <Tile
          label="Pieces with full details"
          value={t.withDetails}
          progress={t.pieces ? t.withDetails / t.pieces : undefined}
          foot={`of ${t.pieces} pieces; older ones get them when matched again`}
        />
        <Tile label="One-way (divided) road" value={directionKm ? `${Math.round((t.oneWayKm / directionKm) * 100)}%` : null} foot={`${t.oneWayKm} km one-way · ${t.twoWayKm} km two-way`} />
        <Tile label="Bridges" value={t.bridges} foot={`${t.bridgeKm} km of bridge · ${t.tunnels} tunnels · ${t.roundabouts} roundabout pieces`} />
        <Tile label="Sharp bends" value={t.sharpCurves} foot="pieces with Valhalla curvature 5 or more (of 15)" />
        <Tile
          label="Readings with their road saved"
          value={readingShare == null ? null : `${Math.round(readingShare * 100)}%`}
          progress={readingShare ?? undefined}
          foot={`last 2 days · ${a.readings.withRoad} of ${a.readings.readings}${a.readings.avgOffM != null ? ` · avg ${a.readings.avgOffM} m off the line, 95% within ${a.readings.p95OffM} m` : ""}`}
        />
        <Tile label="Toll pieces" value={t.tollPieces} foot="this Valhalla build marks no toll roads in India" />
        <Tile label="Marked truck route" value={t.truckRoutePieces} foot="OpenStreetMap hgv routes, rarely tagged in India" />
        <Tile label="Limits from OSM / by hand" value={`${t.osmLimitPieces} / ${t.manualLimitPieces}`} foot="pieces with an OpenStreetMap or a hand-set limit" />
      </Stats>

      <div className="grid-2">
        <Card title="Valhalla's speed against the road class" subtitle="The speed Valhalla routes with (length-weighted), next to what a class means for trucks." flush>
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Class</th>
                  <th>Known</th>
                  <th>Valhalla speed</th>
                  <th>Avg lanes</th>
                </tr>
              </thead>
              <tbody>
                {a.speedByClass.map((r) => (
                  <tr key={r.roadClass}>
                    <td>{classLabel(r.roadClass)}</td>
                    <td className="mono">
                      {r.km} km <span className="muted">· {r.pieces}</span>
                    </td>
                    <td className="mono">{r.valhallaKmph != null ? `${r.valhallaKmph} km/h` : "–"}</td>
                    <td className="mono">{r.lanes}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="Road use and curvature" subtitle="What kind of road piece (main road, ramp, service road …) and how curvy, by length." flush>
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Use</th>
                  <th>Known</th>
                </tr>
              </thead>
              <tbody>
                {a.uses.map((u) => (
                  <tr key={u.use}>
                    <td>{u.use.replaceAll("_", " ")}</td>
                    <td className="mono">
                      {u.km} km <span className="muted">· {u.pieces}</span>
                    </td>
                  </tr>
                ))}
                {a.curvature.length > 0 && (
                  <tr>
                    <td>
                      <strong>Curvature</strong>
                    </td>
                    <td className="mono">
                      {a.curvature.map((c) => (
                        <span key={c.curvature} style={{ marginRight: 12 }}>
                          {c.curvature}: {c.km} km
                        </span>
                      ))}
                    </td>
                  </tr>
                )}
                <tr>
                  <td>
                    <strong>Surface</strong>
                  </td>
                  <td className="muted">
                    {a.surfaces.map((s) => `${s.surface} (${s.pieces})`).join(", ")} - untagged in this map, so not usable yet
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </Card>
      </div>

      <div className="grid-2">
        <PlaceList title="Bridges driven" subtitle="Longest first; the dark-edged lines on the trip replay map." rows={a.bridges.map((b) => ({ ...b, note: `${b.lengthM} m` }))} />
        <PlaceList title="Sharpest bends" subtitle="Curvature 5 or more of 15: candidates for the risk score." rows={a.curves.map((c) => ({ ...c, note: `curvature ${c.curvature} · ${c.lengthM} m` }))} />
      </div>
    </>
  );
}

function PlaceList({ title, subtitle, rows }: { title: string; subtitle: string; rows: { id: number; label: string; roadClass: string; note: string; lat: number; lon: number }[] }) {
  return (
    <Card title={title} subtitle={subtitle} flush>
      {rows.length === 0 ? (
        <Empty>None among the learned roads yet.</Empty>
      ) : (
        <div className="data-table-wrap" style={{ maxHeight: 320, overflow: "auto" }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Road</th>
                <th>Piece</th>
                <th>Where</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    {r.label}
                    <div className="muted">{classLabel(r.roadClass)}</div>
                  </td>
                  <td className="mono">{r.note}</td>
                  <td className="mono">
                    {r.lat}, {r.lon}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
