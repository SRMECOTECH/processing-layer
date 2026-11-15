"use client";

import { useMemo, useState } from "react";
import { Callout, Card, Empty, KeyValues, PageHeader, Tag } from "@/components/ops/kit";
import { Search } from "@/components/icons";
import { StatusTag } from "@/components/roads/module-card";
import { moduleByKey } from "@/lib/road-intelligence";
import { RoadAttributesSection } from "@/components/roads/road-attributes";
import { RoadPieceDetailsView } from "@/components/roads/road-details";
import { RoadEventsCard, RoadPointsSection } from "@/components/roads/road-records";
import { SOURCE_LABEL, classLabel, type KnownRoad, type LimitSource, type RoadPieceDetails } from "@/lib/roads";
import { usePoll } from "@/lib/use-poll";

type Piece = { id: number; label: string; roadClass: string; lanes: number; limitKmph: number | null; limitSource: LimitSource | null; points: [number, number][]; bridge?: boolean; oneWay?: boolean; curvature?: number | null };
type Located = { found: boolean; id?: number; label?: string; roadClass?: string; lanes?: number; density?: number; distanceM?: number; limitKmph?: number; limitSource?: LimitSource; area?: string; details?: RoadPieceDetails };

/** Valhalla road classes, most important first, with their line colour and width on the map. */
const CLASS_STYLE: Record<string, { color: string; width: number }> = {
  motorway: { color: "#7c3aed", width: 3.2 },
  trunk: { color: "#dc2626", width: 2.6 },
  primary: { color: "#ea580c", width: 2.1 },
  secondary: { color: "#ca8a04", width: 1.7 },
  tertiary: { color: "#16a34a", width: 1.4 },
  unclassified: { color: "#0891b2", width: 1.1 },
  residential: { color: "#64748b", width: 1 },
  service_other: { color: "#94a3b8", width: 0.9 },
};

/** A. Road network explorer: the roads our trucks drove, searchable, on a map drawn to scale. */
export default function RoadExplorerPage() {
  const m = moduleByKey("explorer")!;
  const network = usePoll<Piece[]>("/api/roads/network", 120_000);
  const [query, setQuery] = useState("");
  const roads = usePoll<KnownRoad[]>(`/api/roads?limit=200${query ? `&q=${encodeURIComponent(query)}` : ""}`, 60_000);
  const [point, setPoint] = useState("");
  const [located, setLocated] = useState<Located>();

  async function locate() {
    const [lat, lon] = point.split(",").map((v) => Number(v.trim()));
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
    const r = await fetch(`/api/roads/locate?lat=${lat}&lon=${lon}`, { cache: "no-store" });
    setLocated(r.ok ? ((await r.json()) as Located) : { found: false });
  }

  return (
    <div className="stack">
      <PageHeader
        title="A. Road network explorer"
        description={m.summary}
        badges={<StatusTag status={m.status} />}
        meta={[
          ["Pieces", network.data ? String(network.data.length) : "…"],
          ["Source", "road_segment (Valhalla trace_attributes)"],
          ["Map", "drawn to scale, no map tiles"],
        ]}
      />
      <Card title="Road map" subtitle="Every learned road piece, coloured by class. Hover a line for its road and limit; search below highlights a road." flush>
        {!network.data ? (
          <Empty>{network.error ?? "Loading the road pieces…"}</Empty>
        ) : network.data.length === 0 ? (
          <Empty>No roads learned yet: Road Intelligence → Speed & regulations → Learn the roads of a trip.</Empty>
        ) : (
          <RoadMap pieces={network.data} highlight={query.trim().toLowerCase()} />
        )}
      </Card>

      <div className="grid-2">
        <Card title="The road under a point" subtitle="Latitude, longitude - the same lookup the live speed check makes for every reading (nearest piece within 35 m).">
          <div className="toolbar-row">
            <input className="input-box mono" placeholder="22.8260, 86.0765" value={point} onChange={(e) => setPoint(e.target.value)} />
            <button type="button" className="btn primary" onClick={locate}>
              Find the road
            </button>
          </div>
          {located &&
            (located.found ? (
              <KeyValues
                rows={[
                  ["Road", located.label],
                  ["Class", classLabel(located.roadClass)],
                  ["Lanes", located.lanes],
                  ["Area", located.area === "TOWN" ? `town (density ${located.density})` : `open road (density ${located.density})`],
                  ["Limit", `${located.limitKmph ?? "–"} km/h (${located.limitSource ? SOURCE_LABEL[located.limitSource] : "no row"})`],
                  ["Distance from the line", `${located.distanceM} m`],
                ]}
              />
            ) : (
              <p className="muted">No learned road within 35 m.</p>
            ))}
          {located?.found && <RoadPieceDetailsView details={located.details} />}
        </Card>
        <Card title="Legend">
          <div className="stack">
            {Object.entries(CLASS_STYLE).map(([c, st]) => (
              <div key={c} className="toolbar-row">
                <svg width="36" height="10" aria-hidden="true">
                  <line x1="2" y1="5" x2="34" y2="5" stroke={st.color} strokeWidth={st.width + 1} strokeLinecap="round" />
                </svg>
                <span>{classLabel(c)}</span>
              </div>
            ))}
          </div>
        </Card>
      </div>

      <RoadAttributesSection />
      <RoadPointsSection />
      <RoadEventsCard />

      <Card
        title="Roads"
        subtitle="Grouped by number and name, longest first."
        actions={
          <label className="search-field">
            <Search size={14} />
            <input placeholder="NH19, bypass, expressway…" value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
        }
        flush
      >
        {!roads.data ? (
          <Empty>{roads.error ?? "Loading…"}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Road</th>
                  <th>Class</th>
                  <th>Area</th>
                  <th>Length known</th>
                  <th>Limit</th>
                </tr>
              </thead>
              <tbody>
                {roads.data.map((r) => (
                  <tr key={`${r.label}-${r.roadClass}-${r.limitKmph}-${r.stateCode}`}>
                    <td>
                      <strong>{r.label}</strong>
                      {r.ref && r.name && <div className="muted">{r.name}</div>}
                    </td>
                    <td>{classLabel(r.roadClass)}</td>
                    <td>{r.area === "TOWN" ? "town" : "open road"}</td>
                    <td>
                      {r.km} km <span className="muted">· {r.pieces} pieces</span>
                    </td>
                    <td>
                      {r.limitKmph ?? "–"} km/h {r.limitSource && <Tag tone="neutral">{SOURCE_LABEL[r.limitSource]}</Tag>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <Callout tone="planned" title="Next for the explorer">
        One-way and two-way roads, intersections, dead ends, bridges, tunnels and roundabouts: Valhalla already returns them, they are not stored yet.
      </Callout>
    </div>
  );
}

function RoadMap({ pieces, highlight }: { pieces: Piece[]; highlight: string }) {
  const W = 1000;
  const view = useMemo(() => {
    let minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
    for (const p of pieces) {
      for (const [lat, lon] of p.points) {
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
      }
    }
    const midLat = (minLat + maxLat) / 2;
    const kx = Math.cos((midLat * Math.PI) / 180);
    const spanX = Math.max(1e-6, (maxLon - minLon) * kx);
    const spanY = Math.max(1e-6, maxLat - minLat);
    const H = Math.min(900, Math.max(320, Math.round((W * spanY) / spanX)));
    const scale = Math.min((W - 40) / spanX, (H - 40) / spanY);
    const x = (lon: number) => 20 + (lon - minLon) * kx * scale;
    const y = (lat: number) => H - 20 - (lat - minLat) * scale;
    return { H, x, y };
  }, [pieces]);
  const order = Object.keys(CLASS_STYLE).reverse();
  const sorted = useMemo(() => [...pieces].sort((a, b) => order.indexOf(a.roadClass) - order.indexOf(b.roadClass)), [pieces, order]);

  return (
    <svg viewBox={`0 0 ${W} ${view.H}`} width="100%" role="img" aria-label="Map of the learned road pieces">
      {sorted.map((p) => {
        const st = CLASS_STYLE[p.roadClass] ?? { color: "#94a3b8", width: 1 };
        const hit = highlight !== "" && p.label.toLowerCase().includes(highlight);
        return (
          <polyline
            key={p.id}
            points={p.points.map(([lat, lon]) => `${view.x(lon).toFixed(1)},${view.y(lat).toFixed(1)}`).join(" ")}
            fill="none"
            stroke={hit ? "#111827" : st.color}
            strokeWidth={hit ? st.width + 2.5 : st.width}
            strokeOpacity={highlight !== "" && !hit ? 0.25 : 0.9}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <title>{`${p.label} · ${classLabel(p.roadClass)} · ${p.lanes} lane(s) · ${p.limitKmph ?? "–"} km/h${p.oneWay ? " · one-way" : ""}${p.bridge ? " · bridge" : ""}${p.curvature != null && p.curvature >= 5 ? ` · sharp bend (${p.curvature})` : ""}`}</title>
          </polyline>
        );
      })}
    </svg>
  );
}
