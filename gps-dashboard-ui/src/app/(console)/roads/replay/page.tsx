"use client";

import { useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { Callout, Card, Empty, KeyValues, PageHeader, Segmented, Stats, Switch, Tag, Tile } from "@/components/ops/kit";
import { Gauge, Map as MapIcon, Play, RotateCcw, Route, X } from "@/components/icons";
import { formatTime } from "@/lib/format";
import { SOURCE_LABEL, classLabel, type LearningRun, type LimitSource } from "@/lib/roads";
import { post, usePoll } from "@/lib/use-poll";
import styles from "./page.module.css";

/** One stored reading of the replayed truck, with the road and limit the live speed check finds under it. */
type Reading = {
  lat: number;
  lon: number;
  speed: number | null;
  at: string;
  /** Already sent to Valhalla by live matching (before its cursor). */
  sent: boolean;
  roadId?: number;
  road?: string;
  roadClass?: string;
  offM?: number;
  limit?: number | null;
  source?: LimitSource | null;
  area?: "TOWN" | "OPEN" | null;
};

type SpeedEvent = {
  type: string;
  severity: string;
  status: string;
  startedAt: string;
  lat: number;
  lon: number;
  road: string | null;
  roadLimit: string | null;
  limitSource: string | null;
  appliedLimit: string | null;
  peakSpeed: string | null;
  band: string | null;
};

type Live = {
  trips: number[] | null;
  fleet: { running: boolean; round: number; roundsPerDrive: number; framesSent: number; lastMessage: string };
  learning: { valhallaUrl: string; busy: boolean; liveMatching: boolean; lastLiveRun: LearningRun | null; roads: number | null; kilometres: number | null };
  imei: string | null;
  matchedUntil?: string | null;
  total?: number;
  onRoad?: number;
  withLimit?: number;
  readings: Reading[];
  events: SpeedEvent[];
};

type Piece = { id: number; label: string; roadClass: string; lanes: number; limitKmph: number | null; limitSource: LimitSource | null; points: [number, number][] };

/** Jamshedpur and Ghaziabad: the "whole trip" view always spans both ends, even before any road is known. */
const TRIP_ENDS: [number, number][] = [
  [22.8, 86.18],
  [28.67, 77.43],
];

/** Line colour by the road's limit: the speed limit a truck has on that piece of road. */
function limitColor(limit: number | null | undefined): string {
  if (limit == null) return "#94a3b8";
  if (limit <= 40) return "#dc2626";
  if (limit <= 50) return "#ea580c";
  if (limit <= 60) return "#ca8a04";
  if (limit <= 70) return "#65a30d";
  if (limit <= 80) return "#16a34a";
  return "#2563eb";
}

const LIMIT_LEGEND: [string, number | null][] = [
  ["≤ 40", 40],
  ["50", 50],
  ["60", 60],
  ["70", 70],
  ["80", 80],
  ["90 +", 90],
  ["no limit row", null],
];

/**
 * Trip replay: deletes the run and replays trip 28860462 (Jamshedpur → Ghaziabad) from its first ping, and shows
 * the analysis while it happens - the readings arriving, Valhalla matching them onto road pieces every couple of
 * minutes, and the limit the live speed check finds under every reading.
 */
export default function TripReplayPage() {
  const live = usePoll<Live>("/api/roads/replay/live", 3_000);
  const network = usePoll<Piece[]>("/api/roads/network", 10_000);
  const [view, setView] = useState<View>("follow");
  const [matching, setMatching] = useState(false);
  const [asking, setAsking] = useState(false);
  const [autoMatch, setAutoMatch] = useState(true);

  const d = live.data;
  const replaying = d?.fleet.running ?? false;

  // At 120x the service's own run (every 2 minutes) lags ~4 hours of trip behind the truck: while this page is open,
  // ask Valhalla every 5 s so the roads and their limits appear just behind the truck.
  useEffect(() => {
    if (!autoMatch || !replaying || !d?.learning.liveMatching) return;
    const timer = setInterval(() => {
      post("/api/roads/match-live").catch(() => {});
    }, AUTO_MATCH_MS);
    return () => clearInterval(timer);
  }, [autoMatch, replaying, d?.learning.liveMatching]);

  // New pieces learned: draw them now rather than at the next network poll.
  const knownRoads = d?.learning.roads;
  const refreshNetwork = network.refresh;
  useEffect(() => {
    if (knownRoads != null) refreshNetwork();
  }, [knownRoads, refreshNetwork]);
  const readings = useMemo(() => d?.readings ?? [], [d]);
  const last = readings.at(-1);
  const progress = d && d.fleet.roundsPerDrive > 0 ? d.fleet.round / d.fleet.roundsPerDrive : 0;
  const onRoadShare = d?.total ? (d.onRoad ?? 0) / d.total : null;
  const lastRun = d?.learning.lastLiveRun;
  const waiting = readings.filter((r) => !r.sent).length;

  async function matchNow() {
    setMatching(true);
    try {
      await post("/api/roads/match-live");
    } finally {
      setTimeout(() => {
        setMatching(false);
        live.refresh();
        network.refresh();
      }, 2500);
    }
  }

  return (
    <div className="stack">
      <PageHeader
        title="Trip replay (live)"
        description="The one-trip test bed: delete the run and replay smart_truck trip 28860462 (Jamshedpur → Ghaziabad) from its first ping, then watch the analysis happen - the readings arriving, Valhalla map-matching them onto road pieces, and the speed limit the live check finds under every reading."
        meta={[
          ["Trip", d?.trips?.join(", ") ?? "…"],
          ["Device", d?.imei ?? "–"],
          ["Valhalla", d?.learning.valhallaUrl ?? "…"],
        ]}
        actions={
          <div className={styles.controls}>
            <label className={styles.legendItem} title="While this page is open and the trip is replaying">
              <Switch checked={autoMatch} onChange={setAutoMatch} label="Map-match every 5 seconds" />
              Map-match every 5 s
            </label>
            <button type="button" className="btn soft" onClick={matchNow} disabled={matching || !d?.learning.liveMatching}>
              <Route size={15} />
              {matching ? "Matching…" : "Map-match now"}
            </button>
            <button type="button" className="btn danger-soft" onClick={() => setAsking(true)}>
              <RotateCcw size={15} />
              Delete &amp; replay trip
            </button>
          </div>
        }
      />
      {asking &&
        createPortal(
          <ReplayDialog
            onClose={() => setAsking(false)}
            onDone={() => {
              live.refresh();
              network.refresh();
            }}
          />,
          document.body,
        )}

      <Stats columns={5}>
        <Tile
          label="Replay"
          icon={Play}
          value={d ? `${Math.round(progress * 100)}%` : null}
          note={d ? (d.fleet.running ? "running" : "stopped") : undefined}
          progress={progress}
          foot={d ? `${d.fleet.round.toLocaleString()} of ${d.fleet.roundsPerDrive.toLocaleString()} pings sent` : undefined}
        />
        <Tile label="Readings stored" icon={Gauge} value={d?.total ?? null} foot={last ? `latest ${formatTime(last.at)} (trip time)` : undefined} />
        <Tile
          label="On a known road"
          icon={Route}
          value={onRoadShare === null ? null : `${Math.round(onRoadShare * 100)}%`}
          progress={onRoadShare ?? undefined}
          foot={d?.total ? `${d.onRoad} of ${d.total} readings within 35 m of a learned piece` : undefined}
        />
        <Tile label="Road pieces known" icon={MapIcon} value={d?.learning.roads ?? null} foot={d?.learning.kilometres != null ? `${d.learning.kilometres} km of road` : undefined} />
        <Tile
          label="Waiting for Valhalla"
          value={d ? waiting : null}
          tone={waiting > 0 ? "warn" : undefined}
          foot={d?.learning.busy ? "a matching run is going now" : lastRun?.finishedAt ? `last run ${formatTime(lastRun.finishedAt)}` : "no run yet"}
        />
      </Stats>

      <div className={styles.layout}>
        <Card
          title="Map matching, live"
          subtitle="Road pieces coloured by their speed limit; dots are the truck's readings. A hollow amber dot is still waiting for the next Valhalla run, a red one was sent but no road was matched near it."
          actions={
            <Segmented
              label="Map view"
              value={view}
              onChange={setView}
              options={[
                ["follow", "Close-up"],
                ["area", "60 km"],
                ["trip", "Whole trip"],
              ]}
            />
          }
          flush
        >
          {!d ? (
            <Empty>{live.error ?? "Loading the replay…"}</Empty>
          ) : (
            <>
              <ReplayMap readings={readings} pieces={network.data ?? []} view={view} />
              <div className={styles.legend}>
                {LIMIT_LEGEND.map(([text, limit]) => (
                  <span key={text} className={styles.legendItem}>
                    <span className={styles.swatch} style={{ background: limitColor(limit) }} />
                    {text}
                    {limit !== null && " km/h"}
                  </span>
                ))}
                <span className={styles.legendItem}>
                  <span className={styles.dot} style={{ color: "#0f172a", background: "#0f172a" }} /> reading on a road
                </span>
                <span className={styles.legendItem}>
                  <span className={styles.dot} style={{ color: "#d97706" }} /> waiting for Valhalla
                </span>
                <span className={styles.legendItem}>
                  <span className={styles.dot} style={{ color: "#dc2626" }} /> no road matched
                </span>
              </div>
            </>
          )}
        </Card>

        <div className="stack">
          <NowCard reading={last} />
          <Card title="Latest Valhalla run" subtitle={d?.learning.liveMatching ? "Live matching every 2 minutes (wall time)" : "Live matching is switched off"}>
            {lastRun ? (
              <KeyValues
                rows={[
                  ["Finished", lastRun.finishedAt ? formatTime(lastRun.finishedAt) : "running"],
                  ["Points sent", lastRun.pointsSent],
                  ["Road pieces matched", lastRun.roadsMatched],
                  ["New pieces", lastRun.roadsAdded],
                  ["Result", <span key="m" className="muted">{lastRun.message}</span>],
                ]}
              />
            ) : (
              <p className="muted">No live matching run since the service started.</p>
            )}
          </Card>
        </div>
      </div>

      <Card title="Speed against the road limit" subtitle="The last 300 readings: the truck's speed (dark) and the limit found under each reading (coloured steps; gaps where no road is known yet).">
        {readings.length < 2 ? <Empty>Waiting for readings…</Empty> : <SpeedChart readings={readings.slice(-300)} />}
      </Card>

      <div className="grid-2">
        <Card title="Readings as they arrive" subtitle="Newest first: what the live speed check sees for every reading." flush>
          {readings.length === 0 ? (
            <Empty>No readings yet.</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Trip time</th>
                    <th>Speed</th>
                    <th>Road</th>
                    <th>Limit</th>
                    <th>Off line</th>
                  </tr>
                </thead>
                <tbody>
                  {readings
                    .slice(-25)
                    .reverse()
                    .map((r) => (
                      <tr key={r.at} className={r.limit != null && r.speed != null && r.speed > r.limit ? styles.rowOver : undefined}>
                        <td className="mono">{formatTime(r.at)}</td>
                        <td className="mono">{r.speed ?? "–"} km/h</td>
                        <td>
                          {r.road ?? <span className="muted">{r.sent ? "no road matched" : "waiting for Valhalla"}</span>}
                          {r.roadClass && <div className="muted">{classLabel(r.roadClass)}</div>}
                        </td>
                        <td>
                          {r.limit != null ? `${r.limit} km/h` : "–"}
                          {r.source && <div className="muted">{SOURCE_LABEL[r.source]}</div>}
                        </td>
                        <td className="mono">{r.offM != null ? `${r.offM} m` : "–"}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title="Speed events of this run" subtitle="Over-speed episodes and speedometer mismatches, with the road they were judged on." flush>
          {!d || d.events.length === 0 ? (
            <Empty>None yet.</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Started</th>
                    <th>Event</th>
                    <th>Road</th>
                    <th>Peak / limit</th>
                  </tr>
                </thead>
                <tbody>
                  {d.events.map((e) => (
                    <tr key={`${e.type}-${e.startedAt}`}>
                      <td className="mono">{formatTime(e.startedAt)}</td>
                      <td>
                        <Tag tone={e.type === "OVERSPEED" ? "bad" : "warn"}>{e.type === "OVERSPEED" ? "Over-speed" : "Speedometer mismatch"}</Tag>
                        {e.band && <div className="muted">{e.band.toLowerCase()}</div>}
                      </td>
                      <td>
                        {e.road ?? "–"}
                        {e.roadLimit && <div className="muted">road limit {e.roadLimit} km/h</div>}
                      </td>
                      <td className="mono">
                        {e.peakSpeed ? Math.round(Number(e.peakSpeed)) : "–"} / {e.appliedLimit ? Math.round(Number(e.appliedLimit)) : "–"} km/h
                        {e.limitSource && <div className="muted">{SOURCE_LABEL[e.limitSource as LimitSource] ?? e.limitSource}</div>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <Callout tone="info" title="What happens while you watch">
        The simulator replays the trip at 120× (about 1.4 hours for the whole drive). Every reading is checked against the road library the moment it
        arrives (nearest learned piece within 35 m). Every 2 minutes live matching sends the device&apos;s new readings to Valhalla
        (trace_attributes); the pieces it returns are stored in road_segment with their limit (hand-set &gt; OpenStreetMap &gt; road table), and the
        next readings on that road get its limit. At 120× that 2-minute run is about 4 hours of the trip, so while this page is open it asks
        Valhalla every 5 seconds (the switch at the top); &quot;Map-match now&quot; runs that step at once. The company limit (60 km/h) still caps the
        over-speed rule.
      </Callout>
    </div>
  );
}

function NowCard({ reading }: { reading: Reading | undefined }) {
  if (!reading) {
    return (
      <Card title="Now">
        <p className="muted">No reading yet.</p>
      </Card>
    );
  }
  const over = reading.limit != null && reading.speed != null && reading.speed > reading.limit;
  return (
    <Card title="Now" subtitle={`Trip time ${formatTime(reading.at)}`}>
      <div className={styles.speedNow}>
        <span className={`${styles.speedBig} ${over ? styles.over : ""}`}>{reading.speed ?? "–"}</span>
        <span className="muted">km/h</span>
        <span className={`${styles.limitSign} ${reading.limit == null ? styles.none : ""}`}>{reading.limit ?? "none"}</span>
      </div>
      <p className={over ? styles.over : reading.limit == null ? styles.pending : styles.ok} style={{ margin: "6px 0 12px", fontSize: 13 }}>
        {reading.limit == null
          ? reading.road
            ? "On a known road without a limit row"
            : reading.sent
              ? "No road matched here"
              : "Road not known yet: waiting for Valhalla"
          : over
            ? `${reading.speed! - reading.limit} km/h over the road limit`
            : "Within the road limit"}
      </p>
      <KeyValues
        rows={[
          ["Road", reading.road ?? "–"],
          ["Class", reading.roadClass ? classLabel(reading.roadClass) : "–"],
          ["Area", reading.area === "TOWN" ? "town" : reading.area === "OPEN" ? "open road" : "–"],
          ["Limit from", reading.source ? SOURCE_LABEL[reading.source] : "–"],
          ["Off the road line", reading.offM != null ? `${reading.offM} m` : "–"],
          ["Position", <span key="p" className="mono">{`${reading.lat.toFixed(5)}, ${reading.lon.toFixed(5)}`}</span>],
        ]}
      />
    </Card>
  );
}

type View = "follow" | "area" | "trip";

/** Half the width of the window around the truck, in kilometres, for the close-up and the 60 km view. */
const WINDOW_KM: Record<Exclude<View, "trip">, number> = { follow: 9, area: 30 };
const AUTO_MATCH_MS = 5_000;

function ReplayMap({ readings, pieces, view }: { readings: Reading[]; pieces: Piece[]; view: View }) {
  const W = 1000;
  const last = readings.at(-1);

  const frame = useMemo(() => {
    let minLat: number, maxLat: number, minLon: number, maxLon: number;
    if (view !== "trip" && last) {
      const km = WINDOW_KM[view];
      const dLat = km / 111;
      const dLon = km / (111 * Math.cos((last.lat * Math.PI) / 180));
      minLat = last.lat - dLat * 0.62;
      maxLat = last.lat + dLat * 0.62;
      minLon = last.lon - dLon;
      maxLon = last.lon + dLon;
    } else {
      minLat = 90;
      maxLat = -90;
      minLon = 180;
      maxLon = -180;
      const grow = (lat: number, lon: number) => {
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
      };
      TRIP_ENDS.forEach(([a, b]) => grow(a, b));
      readings.forEach((r) => grow(r.lat, r.lon));
    }
    const midLat = (minLat + maxLat) / 2;
    const kx = Math.cos((midLat * Math.PI) / 180);
    const spanX = Math.max(1e-6, (maxLon - minLon) * kx);
    const spanY = Math.max(1e-6, maxLat - minLat);
    const H = view !== "trip" ? 620 : Math.min(760, Math.max(360, Math.round((W * spanY) / spanX)));
    const scale = Math.min((W - 40) / spanX, (H - 40) / spanY);
    const ox = (W - spanX * scale) / 2;
    const oy = (H - spanY * scale) / 2;
    const x = (lon: number) => ox + (lon - minLon) * kx * scale;
    const y = (lat: number) => H - oy - (lat - minLat) * scale;
    const inside = (lat: number, lon: number) => lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
    return { H, x, y, inside };
  }, [view, last, readings]);

  const visiblePieces = useMemo(
    () => (view === "trip" ? pieces : pieces.filter((p) => p.points.some(([lat, lon]) => frame.inside(lat, lon)))),
    [pieces, frame, view],
  );
  const visibleReadings = useMemo(
    () => (view === "trip" ? readings : readings.filter((r) => frame.inside(r.lat, r.lon))),
    [readings, frame, view],
  );
  const follow = view === "follow";
  const roadWidth = follow ? 5 : view === "area" ? 3 : 2;
  const dotR = follow ? 4 : view === "area" ? 2.4 : 1.6;
  const labelW = 132;
  const labelLeft = last ? frame.x(last.lon) + 12 + labelW > W : false;

  return (
    <svg className={styles.map} viewBox={`0 0 ${1000} ${frame.H}`} role="img" aria-label="Map of the replayed trip with the matched roads">
      <defs>
        <clipPath id="replay-clip">
          <rect x="0" y="0" width="1000" height={frame.H} />
        </clipPath>
      </defs>
      <g clipPath="url(#replay-clip)">
        {visiblePieces.map((p) => (
          <polyline
            key={p.id}
            points={p.points.map(([lat, lon]) => `${frame.x(lon).toFixed(1)},${frame.y(lat).toFixed(1)}`).join(" ")}
            fill="none"
            stroke={limitColor(p.limitKmph)}
            strokeWidth={roadWidth}
            strokeOpacity={0.75}
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <title>{`${p.label} · ${classLabel(p.roadClass)} · ${p.limitKmph ?? "–"} km/h${p.limitSource ? ` (${SOURCE_LABEL[p.limitSource]})` : ""}`}</title>
          </polyline>
        ))}
        {visibleReadings.length > 1 && (
          <polyline
            points={visibleReadings.map((r) => `${frame.x(r.lon).toFixed(1)},${frame.y(r.lat).toFixed(1)}`).join(" ")}
            fill="none"
            stroke="#0f172a"
            strokeOpacity={0.35}
            strokeWidth={1}
            strokeDasharray={follow ? "3 3" : undefined}
          />
        )}
        {visibleReadings.map((r) => {
          const known = r.roadId !== undefined;
          const color = known ? "#0f172a" : r.sent ? "#dc2626" : "#d97706";
          return (
            <circle
              key={r.at}
              cx={frame.x(r.lon)}
              cy={frame.y(r.lat)}
              r={known ? dotR : dotR + 1}
              fill={known ? color : "#fff"}
              stroke={color}
              strokeWidth={known ? 0 : follow ? 2 : 1}
            >
              <title>{`${formatTime(r.at)} · ${r.speed ?? "–"} km/h · ${r.road ?? (r.sent ? "no road matched" : "waiting for Valhalla")}${r.limit != null ? ` · limit ${r.limit}` : ""}${r.offM != null ? ` · ${r.offM} m off the line` : ""}`}</title>
            </circle>
          );
        })}
        {last && (
          <g transform={`translate(${frame.x(last.lon)},${frame.y(last.lat)})`}>
            <circle r={follow ? 13 : 9} fill="#2563eb" fillOpacity={0.18} />
            <circle r={follow ? 7 : 5} fill="#2563eb" stroke="#fff" strokeWidth={2} />
            <g transform={`translate(${labelLeft ? -12 - labelW : 12},-14)`}>
              <rect x="0" y="-14" width={labelW} height="22" rx="6" fill="#0f172a" fillOpacity={0.85} />
              <text x="8" y="1" fill="#fff" fontSize={12} fontWeight={600}>
                {`${last.speed ?? "–"} km/h · limit ${last.limit ?? "–"}`}
              </text>
            </g>
          </g>
        )}
      </g>
      {view !== "trip" && (
        <g transform={`translate(20,${frame.H - 20})`}>
          <ScaleBar kmPx={(1000 - 40) / (WINDOW_KM[view] * 2)} km={follow ? 2 : 10} />
        </g>
      )}
    </svg>
  );
}

function ScaleBar({ kmPx, km }: { kmPx: number; km: number }) {
  const w = kmPx * km;
  return (
    <g>
      <line x1="0" y1="0" x2={w} y2="0" stroke="#0f172a" strokeWidth={2} />
      <line x1="0" y1="-5" x2="0" y2="0" stroke="#0f172a" strokeWidth={2} />
      <line x1={w} y1="-5" x2={w} y2="0" stroke="#0f172a" strokeWidth={2} />
      <text x={w + 6} y="4" fontSize={11} fill="#0f172a">
        {km} km
      </text>
    </g>
  );
}

function SpeedChart({ readings }: { readings: Reading[] }) {
  const W = 1000;
  const H = 180;
  const top = 10;
  const bottom = 22;
  const max = Math.max(100, ...readings.map((r) => Math.max(r.speed ?? 0, r.limit ?? 0))) + 10;
  const x = (i: number) => 36 + (i / (readings.length - 1)) * (W - 46);
  const y = (v: number) => top + (1 - v / max) * (H - top - bottom);
  const speedLine = readings.map((r, i) => `${x(i).toFixed(1)},${y(r.speed ?? 0).toFixed(1)}`).join(" ");
  const ticks = [0, 20, 40, 60, 80, 100].filter((t) => t < max);

  return (
    <svg className={styles.chart} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label="Speed and road limit of the latest readings">
      {ticks.map((t) => (
        <g key={t}>
          <line x1={36} x2={W - 10} y1={y(t)} y2={y(t)} stroke="currentColor" strokeOpacity={0.1} />
          <text x={30} y={y(t) + 4} fontSize={10} textAnchor="end" fill="currentColor" fillOpacity={0.55}>
            {t}
          </text>
        </g>
      ))}
      {readings.map((r, i) =>
        i > 0 && r.limit != null ? (
          <line key={r.at} x1={x(i - 1)} x2={x(i)} y1={y(r.limit)} y2={y(r.limit)} stroke={limitColor(r.limit)} strokeWidth={3} />
        ) : null,
      )}
      <polyline points={speedLine} fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinejoin="round" />
      <text x={36} y={H - 6} fontSize={10} fill="currentColor" fillOpacity={0.55}>
        {formatTime(readings[0].at)}
      </text>
      <text x={W - 10} y={H - 6} fontSize={10} textAnchor="end" fill="currentColor" fillOpacity={0.55}>
        {formatTime(readings[readings.length - 1].at)}
      </text>
    </svg>
  );
}

type ReplayAnswer = { trips: number[]; roadsDeleted: number; roadsKept: number; tablesEmptied: number; tookMillis: number; fleet: string; notes: string[] };

function ReplayDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [forgetRoads, setForgetRoads] = useState(true);
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<ReplayAnswer>();
  const [problem, setProblem] = useState<string>();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  async function run() {
    setBusy(true);
    setProblem(undefined);
    try {
      setAnswer(await post<ReplayAnswer>(`/api/roads/replay?forgetRoads=${forgetRoads}`));
      onDone();
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "The replay did not start");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="replay-title">
        <div className="modal-head">
          <h2 id="replay-title" className="card-title">
            {answer ? "Replay started" : "Delete this run and replay the trip?"}
          </h2>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose} disabled={busy}>
            <X size={18} />
          </button>
        </div>
        {!answer ? (
          <>
            <ul className="modal-list">
              <li>Stops the replay and empties Kafka, the readings, trips, stops, events and the day reports</li>
              <li>Starts trip 28860462 again from its first ping in Jamshedpur (120×, about 1.4 hours to Ghaziabad)</li>
            </ul>
            <label className="check" style={{ marginTop: 12 }}>
              <input type="checkbox" checked={forgetRoads} onChange={(e) => setForgetRoads(e.target.checked)} />
              Also forget the learned roads, so Valhalla matches them again live (pieces with a hand-set limit are kept)
            </label>
            <p className="faint" style={{ fontSize: 12.5, margin: "12px 0 16px" }}>
              Kept: vehicles, devices, the shipment, geofences, speed rules and the road limit table.
            </p>
            {problem && <p className="bad">{problem}</p>}
            <div className="card-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn soft" onClick={onClose} disabled={busy}>
                Cancel
              </button>
              <button type="button" className="btn danger" onClick={run} disabled={busy}>
                {busy ? "Deleting (about 10 s)" : "Delete & replay"}
              </button>
            </div>
          </>
        ) : (
          <>
            <KeyValues
              rows={[
                ["Trip", answer.trips?.join(", ") ?? "–"],
                ["Road pieces forgotten", answer.roadsDeleted],
                ["Road pieces kept", answer.roadsKept],
                ["Tables emptied", answer.tablesEmptied],
                ["Took", `${(answer.tookMillis / 1000).toFixed(1)} s`],
                ["Fleet", answer.fleet],
              ]}
            />
            {answer.notes.length > 0 && (
              <ul className="modal-list warn">
                {answer.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            )}
            <div className="card-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn primary" onClick={onClose}>
                Watch it
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
