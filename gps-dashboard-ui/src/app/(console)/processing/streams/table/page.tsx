"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Crosshair, Search, Table2 } from "lucide-react";
import { Callout, Card, Code, Empty, KeyValues, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { StreamsOff, useStreamsOverview } from "@/components/ops/streams";
import { formatAgo, formatCoordinate, formatDecimal, formatDistance, formatDuration, formatNumber, formatTime } from "@/lib/format";
import type { StreamsVehicleView, VehicleSnapshot } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Sort = "recent" | "distance" | "late";

export default function StreamsVehicleTablePage() {
  const overview = useStreamsOverview(10_000);
  const enabled = overview.data?.enabled;
  const table = usePoll<VehicleSnapshot[]>(enabled ? "/api/streams/vehicles?limit=1000" : null, 4000);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<Sort>("recent");
  const [picked, setPicked] = useState<string | null>(null);
  const detail = usePoll<StreamsVehicleView>(picked ? `/api/streams/vehicles/${picked}?windows=8` : null, 4000);

  const all = table.data ?? [];
  const rows = useMemo(() => {
    const q = query.trim();
    const list = all.filter((v) => !q || v.imei.includes(q));
    const by: Record<Sort, (a: VehicleSnapshot, b: VehicleSnapshot) => number> = {
      recent: (a, b) => String(b.recordedAt).localeCompare(String(a.recordedAt)),
      distance: (a, b) => (b.totalDistanceMeters ?? 0) - (a.totalDistanceMeters ?? 0),
      late: (a, b) => b.outOfOrderReadings - a.outOfOrderReadings,
    };
    return [...list].sort(by[sort]);
  }, [all, query, sort]);

  const moving = all.filter((v) => v.movementStatus === "MOVING").length;
  const readings = all.reduce((n, v) => n + v.readings, 0);
  const late = all.reduce((n, v) => n + v.outOfOrderReadings, 0);
  const newest = all.reduce<string | null>((m, v) => (v.recordedAt && (!m || v.recordedAt > m) ? v.recordedAt : m), null);

  return (
    <div className="stack">
      <PageHeader
        description="A KTable: every processed position folded into one row per vehicle, newest by event time. Read straight from the analytics app's RocksDB store through Interactive Queries; with several instances each one answers for its own partitions and the answers are merged."
        meta={[
          ["Store", <Code key="s">vehicle-table-store</Code>],
          ["Changelog", <Code key="c">gps-streams-analytics-vehicle-table-store-changelog</Code>],
          ["API", <Code key="a">GET /api/streams/vehicles</Code>],
        ]}
      />

      {!enabled ? (
        <StreamsOff overview={overview.data} error={overview.error} />
      ) : (
        <>
          <Stats>
            <Tile label="Vehicles in the table" value={table.data ? all.length : undefined} big />
            <Tile label="Moving" value={table.data ? moving : undefined} unit={table.data ? `/ ${all.length}` : undefined} tone="good" big />
            <Tile label="Readings folded in" value={table.data ? readings : undefined} big />
            <Tile label="Late readings" value={table.data ? late : undefined} big foot="counted, never allowed to replace a newer position" />
          </Stats>

          <div className="grid-main-side">
            <Card
              icon={Table2}
              title="Vehicle table"
              subtitle={newest ? `Newest reading ${formatTime(newest)} (event time)` : "Waiting for readings"}
              live
              flush
              actions={
                <Segmented<Sort>
                  label="Sort"
                  value={sort}
                  onChange={setSort}
                  options={[
                    ["recent", "Latest"],
                    ["distance", "Distance"],
                    ["late", "Late"],
                  ]}
                />
              }
            >
              <div className="table-toolbar">
                <label className="search-field">
                  <Search size={15} />
                  <input type="search" placeholder="IMEI" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Filter by IMEI" />
                </label>
                <span className="faint">{table.data ? `${rows.length} vehicles` : ""}</span>
              </div>
              {table.error && !table.data ? (
                <Empty>{table.error}</Empty>
              ) : !table.data ? (
                <Empty>Waiting for the service.</Empty>
              ) : rows.length === 0 ? (
                <Empty>The table is empty: it fills with the first reading of each vehicle.</Empty>
              ) : (
                <div className="data-table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>IMEI</th>
                        <th>Status · for</th>
                        <th className="right">Speed</th>
                        <th className="right">Distance</th>
                        <th className="right">Readings</th>
                        <th className="right">Late</th>
                        <th>Recorded</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.slice(0, 80).map((v) => (
                        <tr key={v.imei} className={`clickable${picked === v.imei ? " selected" : ""}`} onClick={() => setPicked(v.imei)}>
                          <td className="mono">{v.imei}</td>
                          <td className="nowrap">
                            <Tag tone={v.movementStatus === "MOVING" ? "good" : "neutral"}>{v.movementStatus ?? "–"}</Tag>{" "}
                            <span className="mono faint">{sinceText(v)}</span>
                          </td>
                          <td className="right mono">{formatNumber(v.speedKmph)} km/h</td>
                          <td className="right mono">{formatDistance(v.totalDistanceMeters)}</td>
                          <td className="right mono">{formatNumber(v.readings)}</td>
                          <td className={`right mono${v.outOfOrderReadings > 0 ? " warn" : " faint"}`}>{formatNumber(v.outOfOrderReadings)}</td>
                          <td className="muted">{formatTime(v.recordedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <Card icon={Crosshair} title="One vehicle" subtitle={picked ? <span className="mono">{picked}</span> : "Pick a row"} live={picked ? true : undefined}>
              {!picked ? (
                <Empty>Click a vehicle to see where Kafka Streams keeps it and its last activity windows.</Empty>
              ) : !detail.data ? (
                <Empty>{detail.error ?? "Asking the instance that owns this vehicle…"}</Empty>
              ) : (
                <VehicleDetail view={detail.data} />
              )}
            </Card>
          </div>

          <Callout title="Why a KTable and not a Redis hash">
            Both hold the latest state, but this one is built by Kafka Streams from the topic itself: it is restored from its changelog after
            a crash, it moves with its partition when instances are added, and it can be joined with other streams without a network call.
            Redis stays the place every service reads from; this is the stream processor&apos;s own, fault-tolerant view.
          </Callout>
        </>
      )}
    </div>
  );
}

function sinceText(v: VehicleSnapshot): string {
  if (!v.statusSince || !v.recordedAt) return "–";
  return formatDuration((new Date(v.recordedAt).getTime() - new Date(v.statusSince).getTime()) / 1000);
}

function VehicleDetail({ view }: { view: StreamsVehicleView }) {
  const v = view.snapshot;
  return (
    <div className="stack tight">
      <KeyValues
        rows={[
          ["Partition / task", <span key="p" className="mono">p{view.partition} · task 0_{view.partition}</span>],
          ["Answered by", <span key="s" className="mono">{view.servedBy}</span>],
          ["Standby copies", view.standbyHosts.length ? view.standbyHosts.join(", ") : "none"],
          ["Position", <span key="pos" className="mono">{v ? formatCoordinate(v.latitude, v.longitude) : "–"}</span>],
          ["Distance source", v?.distanceSource ?? "–"],
          ["Ignition", v?.ignition === null || v?.ignition === undefined ? "not reported" : v.ignition ? "on" : "off"],
          ["Top speed seen", v ? `${v.maxSpeedKmph} km/h` : "–"],
          ["Left the normalizer", formatAgo(v?.lastProcessedAt)],
        ]}
      />
      <div className="inner-title st-section">Last 5-minute windows</div>
      {view.windows.length === 0 ? (
        <Empty>No windows yet.</Empty>
      ) : (
        <div className="data-table-wrap">
          <table className="data-table compact">
            <thead>
              <tr>
                <th>Window</th>
                <th className="right">Readings</th>
                <th className="right">Avg</th>
                <th className="right">Distance</th>
              </tr>
            </thead>
            <tbody>
              {view.windows.map((w) => (
                <tr key={w.windowStart}>
                  <td className="mono">
                    {formatTime(w.windowStart).slice(0, 5)}–{formatTime(w.windowEnd).slice(0, 5)}
                  </td>
                  <td className="right mono">{w.readings}</td>
                  <td className="right mono muted">{formatDecimal(w.averageSpeedKmph)}</td>
                  <td className="right mono">{formatDistance(w.distanceMeters)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Link className="link" href={`/live/inspector?imei=${view.snapshot?.imei ?? ""}`}>
        Open in the vehicle inspector
      </Link>
    </div>
  );
}
