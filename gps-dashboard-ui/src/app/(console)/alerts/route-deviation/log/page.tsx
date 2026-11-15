"use client";

import Link from "next/link";
import { useState } from "react";
import { History } from "@/components/icons";
import { Callout, Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import { ROUTE_EVENT_LABEL, clock, eventSummary, minutesText, type RouteEvent } from "@/lib/routes";
import { usePoll } from "@/lib/use-poll";

const ROUTE = moduleById("route")!;
type Hours = "24" | "72" | "168";

const severityTone = (s: string) => (s === "CRITICAL" || s === "HIGH" ? "bad" : s === "MEDIUM" ? "warn" : s === "LOW" ? "neutral" : "accent");

/** Every deviation, alternate road, wrong direction, arrival and missed stop, newest first. */
export default function DeviationLogPage() {
  const [hours, setHours] = useState<Hours>("24");
  const { data, error } = usePoll<RouteEvent[]>(`/api/routes/log?hours=${hours}&limit=1000`, 10_000);
  const events = data ?? [];
  const deviations = events.filter((e) => e.type === "ROUTE_DEVIATION");
  const open = deviations.filter((e) => e.status === "OPEN").length;
  const added = deviations.reduce((sum, e) => sum + (typeof e.details.addedMinutes === "number" ? e.details.addedMinutes : 0), 0);
  const extraKm = deviations.reduce((sum, e) => sum + (typeof e.details.extraKm === "number" ? e.details.extraKm : 0), 0);

  return (
    <div className="stack">
      <ModuleHeader
        module={ROUTE}
        title="Deviation log"
        description="Each time a truck left its planned road for a clearly slower way (ROUTE_DEVIATION), took another road about as good (ROUTE_ALTERNATE), drove the wrong way, reached its destination, or missed a planned stop."
      />
      {error && !data && <Callout tone="warn" title="The log did not load">{error}</Callout>}

      <Stats columns={4}>
        <Tile label="Off route now" value={open} tone={open ? "bad" : undefined} big />
        <Tile label="Deviations" value={deviations.length} note={`${minutesText(added)} added in all`} big />
        <Tile label="Extra km driven" value={Math.round(extraKm * 10) / 10} unit="km" big />
        <Tile label="Alternate roads" value={events.filter((e) => e.type === "ROUTE_ALTERNATE").length} tone="good" note="not alarms: as good as the plan" big />
      </Stats>

      <Card
        icon={History}
        title="Route events"
        subtitle="Newest first. Open a trip for its map."
        live={!error}
        flush
        actions={
          <Segmented<Hours>
            label="Window"
            value={hours}
            onChange={setHours}
            options={[
              ["24", "24 h"],
              ["72", "3 days"],
              ["168", "7 days"],
            ]}
          />
        }
      >
        {events.length ? (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>From</th>
                  <th>Until</th>
                  <th>Vehicle</th>
                  <th>Trip</th>
                  <th>Event</th>
                  <th>What happened</th>
                  <th>Severity</th>
                </tr>
              </thead>
              <tbody>
                {events.map((e, i) => (
                  <tr key={i} className={e.status === "OPEN" ? "flagged" : undefined}>
                    <td className="mono">{clock(e.startedAt)}</td>
                    <td className="mono">{e.status === "OPEN" ? <Tag tone="ink">OPEN</Tag> : clock(e.endedAt)}</td>
                    <td className="mono">{e.vehicle}</td>
                    <td>
                      <Link href={`/alerts/route-deviation/trip?id=${String(e.details.planId)}`}>
                        {String(e.details.origin ?? "")} → {String(e.details.destination ?? "")}
                      </Link>
                    </td>
                    <td>{ROUTE_EVENT_LABEL[e.type] ?? e.type}</td>
                    <td>{eventSummary(e)}</td>
                    <td>
                      <Tag tone={severityTone(e.severity)}>{e.severity}</Tag>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty>No route events in this window.</Empty>
        )}
      </Card>
    </div>
  );
}
