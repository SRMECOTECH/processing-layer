"use client";

import { useState } from "react";
import { Clock } from "@/components/icons";
import { Callout, Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import { clock, lateText, lateTone, type LiveRoutes, type Trip, type TripStop } from "@/lib/routes";
import { usePoll } from "@/lib/use-poll";

const WAYPOINTS = moduleById("waypoints")!;
type Show = "ALL" | "AHEAD" | "DONE" | "PROBLEM";

type Row = { trip: Trip; stop: TripStop; verdict: string; tone: "good" | "bad" | "warn" | "neutral" | "accent"; minutes: number | null };

/** Arrival against the plan, or the live ETA against it for a stop still ahead. */
function judge(trip: Trip, s: TripStop): Row {
  const planned = s.plannedAt ? new Date(s.plannedAt).getTime() : null;
  if (s.status === "MISSED") return { trip, stop: s, verdict: "MISSED", tone: "bad", minutes: null };
  const at = s.arrivedAt ?? (s.status === "PENDING" ? s.etaAt : null);
  if (!at || planned === null) {
    return { trip, stop: s, verdict: s.status === "PASSED" ? "DROVE THROUGH" : "EN ROUTE", tone: "neutral", minutes: null };
  }
  const minutes = Math.round((new Date(at).getTime() - planned) / 60_000);
  const verdict = s.arrivedAt ? (minutes > 15 ? "LATE" : minutes < -15 ? "EARLY" : "ON TIME") : minutes > 15 ? "RUNNING LATE" : "EN ROUTE";
  return { trip, stop: s, verdict, tone: minutes > 15 ? "warn" : "good", minutes };
}

/** Every stop of every planned trip: planned time, ETA or actual arrival, and how far off. */
export default function ArrivalsPage() {
  const [show, setShow] = useState<Show>("ALL");
  const { data, error } = usePoll<LiveRoutes>("/api/routes/live?hours=24", 10_000);
  const rows = (data?.trips ?? [])
    .flatMap((t) => t.stops.filter((s) => s.kind === "MANUAL" || s.status !== "PENDING" || s.etaAt).map((s) => judge(t, s)))
    .filter(
      (r) =>
        show === "ALL" ||
        (show === "AHEAD" && r.stop.status === "PENDING") ||
        (show === "DONE" && r.stop.arrivedAt) ||
        (show === "PROBLEM" && (r.verdict === "MISSED" || r.verdict === "LATE" || r.verdict === "RUNNING LATE" || r.stop.outOfOrder)),
    )
    .sort((a, b) => (a.stop.arrivedAt ?? a.stop.etaAt ?? a.stop.plannedAt ?? "").localeCompare(b.stop.arrivedAt ?? b.stop.etaAt ?? b.stop.plannedAt ?? ""));
  const all = (data?.trips ?? []).flatMap((t) => t.stops.map((s) => judge(t, s)));

  return (
    <div className="stack">
      <ModuleHeader
        module={WAYPOINTS}
        title="Arrivals & ETA"
        description="Each stop's planned time (the trip's start plus planned driving at the lane's pace and the stays before it), its live ETA while it is ahead, and the actual arrival once reached. Destination arrivals are on Live trips & ETA."
      />
      {error && !data && <Callout tone="warn" title="Trips did not load">{error}</Callout>}

      <Stats columns={4}>
        <Tile label="On time" value={all.filter((r) => r.verdict === "ON TIME" || r.verdict === "EARLY").length} tone="good" big />
        <Tile label="Late (15 min +)" value={all.filter((r) => r.verdict === "LATE").length} tone="warn" big />
        <Tile label="Running late" value={all.filter((r) => r.verdict === "RUNNING LATE").length} tone="warn" big />
        <Tile label="Missed" value={all.filter((r) => r.verdict === "MISSED").length} tone="bad" big />
      </Stats>

      <Card
        icon={Clock}
        title="Stops"
        subtitle="Planned stops, and geofences on the way once reached or with an ETA."
        live={!error}
        flush
        actions={
          <Segmented<Show>
            label="Show"
            value={show}
            onChange={setShow}
            options={[
              ["ALL", "All"],
              ["AHEAD", "Ahead"],
              ["DONE", "Reached"],
              ["PROBLEM", "Late or missed"],
            ]}
          />
        }
      >
        {rows.length ? (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Trip</th>
                  <th>Stop</th>
                  <th>Planned</th>
                  <th>ETA</th>
                  <th>Arrived</th>
                  <th>Against plan</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.trip.planId}-${r.stop.seq}`} className={r.tone === "bad" ? "flagged" : undefined}>
                    <td className="mono">{r.trip.vehicle}</td>
                    <td>{r.trip.tripNo ?? `${r.trip.origin} → ${r.trip.destination}`}</td>
                    <td>
                      {r.stop.name} <span className="faint">{r.stop.kind === "MANUAL" ? "planned" : "geofence"}</span>
                    </td>
                    <td className="mono">{clock(r.stop.plannedAt)}</td>
                    <td className="mono">{r.stop.status === "PENDING" ? clock(r.stop.etaAt) : "–"}</td>
                    <td className="mono">{clock(r.stop.arrivedAt)}</td>
                    <td>{r.minutes !== null ? <Tag tone={lateTone(r.minutes)}>{lateText(r.minutes)}</Tag> : "–"}</td>
                    <td>
                      <Tag tone={r.tone}>{r.verdict}</Tag> {r.stop.outOfOrder && <Tag tone="warn">OUT OF ORDER</Tag>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty>No stops to show.</Empty>
        )}
      </Card>
    </div>
  );
}
