"use client";

import { useState } from "react";
import { Callout, Card, Empty, KeyValues, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { Map as MapIcon, RotateCcw, Route, Save, Search } from "@/components/icons";
import { formatAgo } from "@/lib/format";
import { SOURCE_LABEL, classLabel, type KnownRoad, type LimitRow, type RoadStatus } from "@/lib/roads";
import { post, sendJson, usePoll } from "@/lib/use-poll";

const TEST_TRIP = "28860462";

/** Road Intelligence H. Road speed limits: the road library learned with Valhalla, the limit table by road class and area, hand-set limits. */
export default function RoadLimitsPage() {
  const status = usePoll<RoadStatus>("/api/roads/status", 3000);
  const table = usePoll<{ rows: LimitRow[]; townDensityFrom: number }>("/api/roads/limits", 30_000);
  const [query, setQuery] = useState("");
  const roads = usePoll<KnownRoad[]>(`/api/roads?limit=300${query ? `&q=${encodeURIComponent(query)}` : ""}`, 15_000);
  const [trip, setTrip] = useState(TEST_TRIP);
  const [message, setMessage] = useState<string>();
  const s = status.data;
  const run = s?.lastRun;

  async function learn() {
    setMessage(undefined);
    try {
      const answer = await post<RoadStatus & { started: boolean }>(`/api/roads/learn?trip=${encodeURIComponent(trip.trim())}`);
      setMessage(answer.started ? `Learning the roads of trip ${trip}…` : "A learning run is already going");
      status.refresh();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    }
  }

  async function matchLive() {
    setMessage(undefined);
    try {
      await post<RoadStatus>("/api/roads/match-live");
      setMessage("Matching the live readings now");
      status.refresh();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="stack">
      <PageHeader
        title="H. Road speed & regulatory intelligence"
        description="Every reading is judged by the limit of the road it is on. The roads come from Valhalla's map matching on the all-India OpenStreetMap (the HP home server); each road's limit is the one set by hand, else OpenStreetMap's, else this table by road class and area. The company's limit for the vehicle stays a cap: a road can only make it stricter."
        meta={[
          ["Valhalla", s?.valhallaUrl ?? "…"],
          ["Tables", "road_segment · road_speed_limit · road_match_cursor"],
          ["Live matching", s ? (s.liveMatching ? "every 2 min" : "off") : "…"],
        ]}
      />

      <Stats columns={4}>
        <Tile label="Road pieces known" icon={Route} value={s?.roads ?? null} />
        <Tile label="Road length known" icon={MapIcon} value={s?.kilometres ?? null} unit="km" />
        <Tile label="Newest learning run" value={run?.roadsMatched ?? null} unit="pieces" note={run ? `${run.roadsAdded} new` : undefined} />
        <Tile label="Valhalla time" value={run ? Math.round(run.valhallaMillis / 100) / 10 : null} unit="s" note={run ? `${run.pieces} calls` : undefined} />
      </Stats>

      <div className="grid-2">
        <Card title="Learn the roads of a trip" subtitle="Map-matches every ping of a smart_truck trip (read-only) and keeps its roads, so the live check knows them from the first reading.">
          <div className="form-grid">
            <label className="field">
              <span className="field-label">smart_truck trip number</span>
              <input className="input-box mono" value={trip} onChange={(e) => setTrip(e.target.value)} />
              <span className="field-hint">{TEST_TRIP} = the test trip, Jamshedpur → Ghaziabad, 1,381 km</span>
            </label>
          </div>
          <div className="toolbar-row">
            <button type="button" className="btn primary" disabled={!trip.trim() || s?.busy} onClick={learn}>
              <Route size={15} /> Learn its roads
            </button>
            <button type="button" className="btn soft" disabled={s?.busy} onClick={matchLive}>
              <RotateCcw size={15} /> Match live readings now
            </button>
          </div>
          {message && <p className="muted">{message}</p>}
        </Card>
        <Card title="Newest runs">
          {run ? (
            <KeyValues
              rows={[
                ["Learning", <span key="w">{run.what}{run.running && <> <Tag tone="accent">running</Tag></>}</span>],
                ["Result", run.message],
                ["Points sent", `${run.pointsSent} (after leaving out a standing truck's repeats)`],
                ["Pieces not matchable", run.failedPieces],
                ["Finished", run.finishedAt ? formatAgo(run.finishedAt) : "–"],
                ["Live matching", s?.lastLiveRun ? `${s.lastLiveRun.message} · ${formatAgo(s.lastLiveRun.finishedAt)}` : "not run yet"],
              ]}
            />
          ) : (
            <Empty>Loading…</Empty>
          )}
        </Card>
      </div>

      <LimitTable rows={table.data?.rows} townDensityFrom={table.data?.townDensityFrom} onSaved={table.refresh} />

      <Card
        title="Known roads"
        subtitle="Grouped by road number and name. Set a limit on a whole road (a bypass through a town, a ghat section); it beats OpenStreetMap and the table."
        actions={
          <label className="search-field">
            <Search size={14} />
            <input placeholder="NH19, Ranchi Bypass…" value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
        }
        flush
      >
        {!roads.data ? (
          <Empty>{roads.error ?? "Loading…"}</Empty>
        ) : roads.data.length === 0 ? (
          <Empty>No roads yet: learn a trip, or start the simulator and wait for the live matching.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Road</th>
                  <th>Class</th>
                  <th>Area</th>
                  <th>Known</th>
                  <th>Limit</th>
                  <th>Set by hand</th>
                </tr>
              </thead>
              <tbody>
                {roads.data.map((road) => (
                  <KnownRoadRow key={`${road.label}-${road.roadClass}-${road.stateCode}-${road.limitKmph}`} road={road} onSaved={roads.refresh} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function LimitTable({ rows, townDensityFrom, onSaved }: { rows?: LimitRow[]; townDensityFrom?: number; onSaved: () => void }) {
  const [edits, setEdits] = useState<Record<string, Partial<LimitRow>>>({});
  const [problem, setProblem] = useState<string>();
  const key = (r: LimitRow) => `${r.roadClass}|${r.area}|${r.stateCode}`;

  async function save(row: LimitRow) {
    setProblem(undefined);
    try {
      await sendJson("/api/roads/limits", "PUT", { ...row, ...edits[key(row)] });
      setEdits((e) => {
        const next = { ...e };
        delete next[key(row)];
        return next;
      });
      onSaved();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <Card
      title="Limit table for goods vehicles"
      subtitle={`By road class and area: a road counts as in a town from Valhalla density ${townDensityFrom ?? "…"} (0..15). Multi-lane = a divided road with 2+ lanes each way. Starting values; check them for each state.`}
      flush
    >
      {problem && <Callout tone="warn">{problem}</Callout>}
      {!rows ? (
        <Empty>Loading…</Empty>
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Road class</th>
                <th>Area</th>
                <th>State</th>
                <th>Limit (km/h)</th>
                <th>Multi-lane (km/h)</th>
                <th aria-label="Save" />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const edit = edits[key(row)] ?? {};
                const limit = edit.limitKmph ?? row.limitKmph;
                const multi = edit.multiLaneLimitKmph !== undefined ? edit.multiLaneLimitKmph : row.multiLaneLimitKmph;
                const changed = Object.keys(edit).length > 0;
                return (
                  <tr key={key(row)}>
                    <td>{classLabel(row.roadClass)}</td>
                    <td>{row.area === "TOWN" ? "town" : "open road"}</td>
                    <td>{row.stateCode === "*" ? "all India" : row.stateCode}</td>
                    <td>
                      <input
                        className="input-box mono"
                        type="number"
                        min={5}
                        max={150}
                        value={limit}
                        onChange={(e) => setEdits((x) => ({ ...x, [key(row)]: { ...edit, limitKmph: Number(e.target.value) } }))}
                      />
                    </td>
                    <td>
                      <input
                        className="input-box mono"
                        type="number"
                        min={5}
                        max={150}
                        placeholder="same"
                        value={multi ?? ""}
                        onChange={(e) =>
                          setEdits((x) => ({ ...x, [key(row)]: { ...edit, multiLaneLimitKmph: e.target.value === "" ? null : Number(e.target.value) } }))
                        }
                      />
                    </td>
                    <td>
                      <button type="button" className="btn soft" disabled={!changed} onClick={() => save(row)}>
                        <Save size={14} /> Save
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function KnownRoadRow({ road, onSaved }: { road: KnownRoad; onSaved: () => void }) {
  const [value, setValue] = useState("");
  const [problem, setProblem] = useState<string>();

  async function save(clear: boolean) {
    setProblem(undefined);
    try {
      await sendJson("/api/roads/limit", "PUT", {
        ref: road.ref,
        name: road.name,
        limitKmph: clear ? null : Number(value),
        note: "set on the Road limits page",
      });
      setValue("");
      onSaved();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <tr>
      <td>
        <strong>{road.label}</strong>
        {road.stateCode && <div className="muted">{road.stateCode}</div>}
      </td>
      <td>{classLabel(road.roadClass)}</td>
      <td>{road.area === "TOWN" ? "town" : "open road"}</td>
      <td>
        {road.km} km
        <div className="muted">{road.pieces} pieces</div>
      </td>
      <td>
        {road.limitKmph ?? "–"} km/h
        <div className="muted">{road.limitSource ? SOURCE_LABEL[road.limitSource] : "no row in the table"}</div>
      </td>
      <td>
        <div className="toolbar-row">
          <input className="input-box mono" type="number" min={5} max={150} placeholder="km/h" value={value} onChange={(e) => setValue(e.target.value)} />
          <button type="button" className="btn soft" disabled={!value} onClick={() => save(false)}>
            Set
          </button>
          {road.limitSource === "MANUAL" && (
            <button type="button" className="btn soft" onClick={() => save(true)}>
              Clear
            </button>
          )}
        </div>
        {problem && <div className="muted">{problem}</div>}
      </td>
    </tr>
  );
}
