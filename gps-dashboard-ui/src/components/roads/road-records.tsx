"use client";

import { Card, Empty, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatDateTime, formatTime } from "@/lib/format";
import { classLabel } from "@/lib/roads";
import { usePoll } from "@/lib/use-poll";

/* Road Intelligence records of V14: points on the road, events by road, Valhalla's run log, limit history. */

type PointsAnswer = {
  totals: { points: number; junctions: number; bigJunctions: number; tollBooths: number; gates: number; borders: number; signals: number; forks: number };
  types: { type: string; points: number }[];
  special: { id: number; type: string; lat: number; lon: number; timesSeen: number; signal: boolean; sideRoadClasses: string | null; road: string | null }[];
  junctions: { id: number; lat: number; lon: number; sideRoads: number; sideRoadClasses: string | null; timesSeen: number; fork: boolean; road: string | null }[];
};

const POINT_LABEL: Record<string, string> = {
  street_intersection: "junction",
  toll_booth: "toll booth",
  gate: "gate",
  border_control: "border control",
  bollard: "bollard",
  multi_use_transit_stop: "transit stop",
  unknown: "unknown",
};

export function pointLabel(type: string): string {
  return POINT_LABEL[type] ?? type.replaceAll("_", " ");
}

function sideRoads(classes: string | null): string {
  if (!classes) return "–";
  const counts = new Map<string, number>();
  for (const c of classes.split(",")) counts.set(c, (counts.get(c) ?? 0) + 1);
  return [...counts.entries()].map(([c, n]) => `${n}× ${classLabel(c).toLowerCase()}`).join(", ");
}

/** 5. Points on the road: junctions, toll booths, gates, borders, signals. */
export function RoadPointsSection() {
  const poll = usePoll<PointsAnswer>("/api/roads/points", 30_000);
  const a = poll.data;
  if (!a) {
    return (
      <Card title="Points on the road">
        <Empty>{poll.error ?? "Loading…"}</Empty>
      </Card>
    );
  }
  const t = a.totals;
  return (
    <>
      <Stats columns={4}>
        <Tile label="Points on the road" value={t.points} foot="the end of every matched road piece (road_point)" />
        <Tile label="Junctions" value={t.junctions} foot={`${t.bigJunctions} with 2 or more side roads · ${t.forks} forks`} />
        <Tile label="Toll booths" value={t.tollBooths} tone={t.tollBooths > 0 ? "warn" : undefined} foot={`${t.gates} gates · ${t.borders} border controls`} />
        <Tile label="Traffic signals" value={t.signals} foot="rarely tagged in India's OpenStreetMap" />
      </Stats>
      <div className="grid-2">
        <Card title="Toll booths, gates, borders and signals" subtitle="Every point that is not a plain junction, in the order they were first driven." flush>
          {a.special.length === 0 ? (
            <Empty>None on the learned roads yet.</Empty>
          ) : (
            <div className="data-table-wrap" style={{ maxHeight: 320, overflow: "auto" }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Point</th>
                    <th>On road</th>
                    <th>Where</th>
                    <th>Passed</th>
                  </tr>
                </thead>
                <tbody>
                  {a.special.map((p) => (
                    <tr key={p.id}>
                      <td>
                        <Tag tone={p.type === "toll_booth" ? "warn" : "neutral"}>{pointLabel(p.type)}</Tag>
                        {p.signal && <div className="muted">traffic signal</div>}
                      </td>
                      <td>{p.road ?? "–"}</td>
                      <td className="mono">
                        {p.lat}, {p.lon}
                      </td>
                      <td className="mono">{p.timesSeen}×</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
        <Card title="Biggest junctions" subtitle="Where most roads meet: candidates for delays and the risk score." flush>
          {a.junctions.length === 0 ? (
            <Empty>No junction with 2 or more side roads yet.</Empty>
          ) : (
            <div className="data-table-wrap" style={{ maxHeight: 320, overflow: "auto" }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>On road</th>
                    <th>Side roads</th>
                    <th>Where</th>
                  </tr>
                </thead>
                <tbody>
                  {a.junctions.map((j) => (
                    <tr key={j.id}>
                      <td>
                        {j.road ?? "–"}
                        {j.fork && <div className="muted">fork</div>}
                      </td>
                      <td>{sideRoads(j.sideRoadClasses)}</td>
                      <td className="mono">
                        {j.lat}, {j.lon}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}

type EventsAnswer = {
  coverage: { events: number; withRoad: number };
  roads: { road: string; roadClass: string | null; events: number; vehicles: number; overspeed: number; stops: number; harsh: number; route: number; ignition: number; geofence: number; other: number }[];
  types: { type: string; events: number; withRoad: number }[];
};

/** 6. Vehicle events by road. */
export function RoadEventsCard({ imei, title = "Events by road" }: { imei?: string | null; title?: string }) {
  const poll = usePoll<EventsAnswer>(`/api/roads/events-by-road${imei ? `?imei=${encodeURIComponent(imei)}` : ""}`, 20_000);
  const a = poll.data;
  return (
    <Card
      title={title}
      subtitle={
        a
          ? `Each event gets the road saved on the reading at its start, after the Valhalla run covers it: ${a.coverage.withRoad} of ${a.coverage.events} events have their road.`
          : "Each event gets the road saved on the reading at its start."
      }
      flush
    >
      {!a ? (
        <Empty>{poll.error ?? "Loading…"}</Empty>
      ) : a.roads.length === 0 ? (
        <Empty>No event has its road yet.</Empty>
      ) : (
        <div className="data-table-wrap" style={{ maxHeight: 360, overflow: "auto" }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Road</th>
                <th>Events</th>
                <th>Over-speed</th>
                <th>Stops / parked</th>
                <th>Ignition</th>
                <th>Geofence</th>
                <th>Route</th>
                <th>Other</th>
              </tr>
            </thead>
            <tbody>
              {a.roads.map((r) => (
                <tr key={`${r.road}-${r.roadClass}`}>
                  <td>
                    <strong>{r.road}</strong>
                    <div className="muted">
                      {r.roadClass ? classLabel(r.roadClass) : ""}
                      {r.vehicles > 1 && ` · ${r.vehicles} vehicles`}
                    </div>
                  </td>
                  <td className="mono">{r.events}</td>
                  <td className="mono">{r.overspeed > 0 ? <Tag tone="bad">{r.overspeed}</Tag> : "–"}</td>
                  <td className="mono">{r.stops || "–"}</td>
                  <td className="mono">{r.ignition || "–"}</td>
                  <td className="mono">{r.geofence || "–"}</td>
                  <td className="mono">{r.route || "–"}</td>
                  <td className="mono">{r.other || "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

type RunsAnswer = {
  day: { runs: number; ok: number; partly: number; down: number; failed: number; pointsSent: number; matchedPoints: number; unmatchedPoints: number; piecesAdded: number; avgMs: number | null; maxMs: number | null; version: string | null; tilesBuiltAt: string | null };
  runs: {
    id: number;
    startedAt: string;
    kind: string;
    imei: string | null;
    tripNumber: number | null;
    readingsFrom: string | null;
    readingsUntil: string | null;
    pointsSent: number;
    matchedPoints: number;
    unmatchedPoints: number;
    calls: number;
    failedPieces: number;
    piecesMatched: number;
    piecesAdded: number;
    pointsFound: number;
    valhallaMs: number;
    outcome: string;
    message: string | null;
  }[];
};

const OUTCOME_TONE: Record<string, "good" | "warn" | "bad"> = { OK: "good", PARTLY: "warn", DOWN: "bad", FAILED: "bad" };

/** 8. The log of Valhalla map-matching runs. */
export function MatchRunsCard({ limit = 25 }: { limit?: number }) {
  const poll = usePoll<RunsAnswer>(`/api/roads/match-runs?limit=${limit}`, 10_000);
  const a = poll.data;
  const d = a?.day;
  return (
    <Card
      title="Valhalla runs"
      subtitle={
        d
          ? `Last 24 h: ${d.runs} runs (${d.ok} ok, ${d.partly} partly, ${d.down} Valhalla down, ${d.failed} failed) · ${d.matchedPoints} of ${d.pointsSent} points matched · ${d.piecesAdded} new pieces · avg ${d.avgMs ?? "–"} ms, slowest ${d.maxMs ?? "–"} ms${d.version ? ` · Valhalla ${d.version}` : ""}${d.tilesBuiltAt ? `, map built ${formatDateTime(d.tilesBuiltAt)}` : ""}`
          : "Every map-matching run (road_match_run)."
      }
      flush
    >
      {!a ? (
        <Empty>{poll.error ?? "Loading…"}</Empty>
      ) : a.runs.length === 0 ? (
        <Empty>No run logged yet.</Empty>
      ) : (
        <div className="data-table-wrap" style={{ maxHeight: 360, overflow: "auto" }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Started</th>
                <th>What</th>
                <th>Points</th>
                <th>Road pieces</th>
                <th>Points on road</th>
                <th>Valhalla</th>
                <th>Outcome</th>
              </tr>
            </thead>
            <tbody>
              {a.runs.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{formatTime(r.startedAt)}</td>
                  <td>
                    {r.kind === "TRIP" ? `trip ${r.tripNumber}` : "live readings"}
                    {r.readingsFrom && r.readingsUntil && (
                      <div className="muted">
                        {formatTime(r.readingsFrom)} → {formatTime(r.readingsUntil)}
                      </div>
                    )}
                  </td>
                  <td className="mono">
                    {r.matchedPoints} / {r.pointsSent}
                    {r.unmatchedPoints > 0 && <div className="muted">{r.unmatchedPoints} unmatched</div>}
                  </td>
                  <td className="mono">
                    {r.piecesMatched} <span className="muted">· {r.piecesAdded} new</span>
                  </td>
                  <td className="mono">{r.pointsFound}</td>
                  <td className="mono">
                    {r.valhallaMs} ms <span className="muted">· {r.calls} call{r.calls === 1 ? "" : "s"}</span>
                  </td>
                  <td>
                    <Tag tone={OUTCOME_TONE[r.outcome] ?? "neutral"}>{r.outcome.toLowerCase()}</Tag>
                    {r.message && <div className="muted" style={{ maxWidth: 260 }}>{r.message}</div>}
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

type LimitChange = {
  id: number;
  changedAt: string;
  changedBy: string;
  scope: string;
  target: string;
  pieces: number | null;
  oldLimitKmph: number | null;
  newLimitKmph: number | null;
  oldMultiLaneKmph: number | null;
  newMultiLaneKmph: number | null;
  note: string | null;
};

const SCOPE_LABEL: Record<string, string> = { PIECE: "one piece", ROAD: "whole road", TABLE_ROW: "table row", TABLE_ROW_DELETED: "table row deleted" };

function kmph(v: number | null): string {
  return v == null ? "none" : `${v}`;
}

/** 7. The history of speed-limit changes. */
export function LimitChangesCard() {
  const poll = usePoll<LimitChange[]>("/api/roads/limit-changes?limit=100", 15_000);
  return (
    <Card title="Limit change history" subtitle="Every change to a hand-set limit or the limit table: who, when, from what to what (road_limit_change)." flush>
      {!poll.data ? (
        <Empty>{poll.error ?? "Loading…"}</Empty>
      ) : poll.data.length === 0 ? (
        <Empty>No limit changed yet.</Empty>
      ) : (
        <div className="data-table-wrap" style={{ maxHeight: 360, overflow: "auto" }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Who</th>
                <th>What</th>
                <th>Limit</th>
                <th>Note</th>
              </tr>
            </thead>
            <tbody>
              {poll.data.map((c) => (
                <tr key={c.id}>
                  <td className="mono">{formatDateTime(c.changedAt)}</td>
                  <td>{c.changedBy}</td>
                  <td>
                    {c.target}
                    <div className="muted">
                      {SCOPE_LABEL[c.scope] ?? c.scope}
                      {c.pieces != null && c.pieces > 1 && ` · ${c.pieces} pieces`}
                    </div>
                  </td>
                  <td className="mono">
                    {kmph(c.oldLimitKmph)} → {kmph(c.newLimitKmph)} km/h
                    {(c.oldMultiLaneKmph != null || c.newMultiLaneKmph != null) && (
                      <div className="muted">
                        multi-lane {kmph(c.oldMultiLaneKmph)} → {kmph(c.newMultiLaneKmph)}
                      </div>
                    )}
                  </td>
                  <td className="muted">{c.note ?? "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
