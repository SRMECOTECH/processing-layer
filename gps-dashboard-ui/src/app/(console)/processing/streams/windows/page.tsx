"use client";

import { useState } from "react";
import { CalendarClock, ChartColumn, ListOrdered } from "lucide-react";
import { Card, Code, Empty, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { SettingsCard } from "@/components/ops/module";
import { StreamsOff, formatSeconds, useStreamsOverview } from "@/components/ops/streams";
import { formatDecimal, formatDistance, formatNumber, formatPercent, formatTime } from "@/lib/format";
import type { FleetWindow } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Measure = "readings" | "distance" | "moving";

const MEASURE: Record<Measure, { label: string; value: (w: FleetWindow) => number; format: (v: number) => string }> = {
  readings: { label: "Readings", value: (w) => w.readings, format: formatNumber },
  distance: { label: "Distance", value: (w) => w.distanceMeters, format: formatDistance },
  moving: { label: "Moving share", value: (w) => w.movingShare, format: formatPercent },
};

export default function StreamsWindowsPage() {
  const overview = useStreamsOverview(10_000);
  const enabled = overview.data?.enabled;
  const s = overview.data?.settings;
  const fleet = usePoll<FleetWindow[]>(enabled ? "/api/streams/fleet/activity?windows=24" : null, 5000);
  const [measure, setMeasure] = useState<Measure>("readings");

  const windows = fleet.data ?? [];
  const closed = windows.filter((w) => !w.open);
  const latestClosed = closed[0];
  const late = windows.reduce((n, w) => n + w.outOfOrderReadings, 0);

  return (
    <div className="stack">
      <PageHeader
        description="Each vehicle's readings, counted in 5-minute windows of EVENT time - when the device took the reading, not when it arrived. A late reading still lands in its own window if it is within the grace period; once stream time passes a window's end plus grace, its final numbers are published once to the output topic."
        meta={[
          ["Window", formatSeconds(s?.windowSizeSeconds)],
          ["Grace", formatSeconds(s?.windowGraceSeconds)],
          ["Queryable for", formatSeconds(s?.windowRetentionSeconds)],
          ["Output topic", <Code key="t">gps.vehicle-activity</Code>],
          ["Store", <Code key="s">activity-window-store</Code>],
        ]}
      />

      {!enabled ? (
        <StreamsOff overview={overview.data} error={overview.error} />
      ) : (
        <>
          <Stats>
            <Tile label="Vehicles · last closed window" value={latestClosed?.vehicles} big foot={latestClosed ? windowLabel(latestClosed) : "no window closed yet"} />
            <Tile label="Readings · last closed window" value={latestClosed?.readings} big />
            <Tile label="Moving share" value={latestClosed ? formatPercent(latestClosed.movingShare) : undefined} big />
            <Tile label="Late, still counted" value={fleet.data ? late : undefined} big foot="arrived after a newer reading, within the grace" />
          </Stats>

          <Card
            icon={ChartColumn}
            title="Fleet per window"
            subtitle="Every vehicle's window summed at query time - no fleet-wide key, so no single hot partition. Lighter bars are still open."
            live
            actions={
              <Segmented<Measure>
                label="Measure"
                value={measure}
                onChange={setMeasure}
                options={[
                  ["readings", "Readings"],
                  ["distance", "Distance"],
                  ["moving", "Moving"],
                ]}
              />
            }
          >
            {fleet.error && !fleet.data ? (
              <Empty>{fleet.error}</Empty>
            ) : windows.length === 0 ? (
              <Empty>{fleet.data ? "No windows yet: they fill as readings arrive." : "Waiting for the service."}</Empty>
            ) : (
              <WindowBars windows={[...windows].reverse()} measure={measure} />
            )}
          </Card>

          <div className="grid-main-side">
            <Card icon={ListOrdered} title="Windows" subtitle="Newest first. A window is open until stream time passes its end plus the grace period." live flush>
              {windows.length === 0 ? (
                <Empty>No windows yet.</Empty>
              ) : (
                <div className="data-table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Window</th>
                        <th>State</th>
                        <th className="right">Vehicles</th>
                        <th className="right">Readings</th>
                        <th className="right">Moving</th>
                        <th className="right">Avg speed</th>
                        <th className="right">Top speed</th>
                        <th className="right">Distance</th>
                        <th className="right">Late</th>
                      </tr>
                    </thead>
                    <tbody>
                      {windows.map((w) => (
                        <tr key={w.windowStart}>
                          <td className="mono">{windowLabel(w)}</td>
                          <td>
                            <Tag tone={w.open ? "warn" : "good"}>{w.open ? "OPEN" : "CLOSED"}</Tag>
                          </td>
                          <td className="right mono">{formatNumber(w.vehicles)}</td>
                          <td className="right mono">{formatNumber(w.readings)}</td>
                          <td className="right mono muted">{formatPercent(w.movingShare)}</td>
                          <td className="right mono muted">{formatDecimal(w.averageSpeedKmph)} km/h</td>
                          <td className="right mono">{w.maxSpeedKmph} km/h</td>
                          <td className="right mono">{formatDistance(w.distanceMeters)}</td>
                          <td className={`right mono${w.outOfOrderReadings > 0 ? " warn" : " faint"}`}>{formatNumber(w.outOfOrderReadings)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <div className="stack">
              <SettingsCard
                title="Window settings"
                source="gps.streams"
                live
                settings={[
                  { label: "Size", value: formatSeconds(s?.windowSizeSeconds), hint: "tumbling, aligned to the clock" },
                  { label: "Grace", value: formatSeconds(s?.windowGraceSeconds), hint: "how late a reading may still count" },
                  { label: "Retention", value: formatSeconds(s?.windowRetentionSeconds), hint: "how long closed windows stay queryable" },
                  { label: "Emit", value: "on close", hint: "one final record per vehicle and window" },
                ]}
              />
              <Card icon={CalendarClock} title="Event time, not arrival time">
                <ol className="steps">
                  <li>
                    <span className="step-n">1</span>
                    <span>The timestamp extractor reads recordedAt from each position: windows follow the device&apos;s clock.</span>
                  </li>
                  <li>
                    <span className="step-n">2</span>
                    <span>Stream time is the newest recordedAt a task has seen; it only moves forward.</span>
                  </li>
                  <li>
                    <span className="step-n">3</span>
                    <span>A reading older than its window&apos;s end plus grace is dropped from the windows (counted as dropped records).</span>
                  </li>
                  <li>
                    <span className="step-n">4</span>
                    <span>The fleet catch-up replays 2 hours at 30×, so windows close fast at first, then every 5 minutes.</span>
                  </li>
                </ol>
              </Card>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function windowLabel(w: FleetWindow): string {
  return `${formatTime(w.windowStart).slice(0, 5)}–${formatTime(w.windowEnd).slice(0, 5)}`;
}

/** Oldest left. One bar per window; open windows are drawn lighter and marked. */
function WindowBars({ windows, measure }: { windows: FleetWindow[]; measure: Measure }) {
  const m = MEASURE[measure];
  const max = Math.max(...windows.map(m.value), measure === "moving" ? 0.0001 : 1);
  return (
    <div className="st-bars" role="img" aria-label={`${m.label} per 5-minute window`}>
      {windows.map((w) => {
        const value = m.value(w);
        return (
          <div key={w.windowStart} className={`st-bar${w.open ? " open" : ""}`} title={`${windowLabel(w)} · ${m.format(value)}${w.open ? " · open" : ""}`}>
            <span className="st-bar-value mono">{m.format(value)}</span>
            <span className="st-bar-track">
              <span className="st-bar-fill" style={{ height: `${Math.max(2, (value / max) * 100)}%` }} />
            </span>
            <span className="st-bar-label mono">{formatTime(w.windowStart).slice(0, 5)}</span>
          </div>
        );
      })}
    </div>
  );
}
