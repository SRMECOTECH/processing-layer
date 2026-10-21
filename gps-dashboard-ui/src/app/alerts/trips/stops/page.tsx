"use client";

import { useState } from "react";
import { MapPin } from "lucide-react";
import { Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { formatCoordinate, formatDateTime, formatDuration, formatNumber, vehicleName } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { StopView, TripCounts } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const TRIPS = moduleById("trips")!;
type Filter = "ALL" | "OPEN" | "CLOSED";

export default function StopsPage() {
  const stops = usePoll<StopView[]>("/api/stops?limit=500", 5000);
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const [filter, setFilter] = useState<Filter>("ALL");
  const list = (stops.data ?? []).filter((s) => filter === "ALL" || s.status === filter);
  const closed = (stops.data ?? []).filter((s) => s.status === "CLOSED");
  const average = closed.length ? closed.reduce((a, s) => a + s.durationSeconds, 0) / closed.length : undefined;

  return (
    <div className="stack">
      <ModuleHeader module={TRIPS} title="Stops" description="The stops between trips, found by trip detection (gps_stop). A stop closes when the vehicle starts its next trip." />

      <Stats columns={3}>
        <Tile label="Standing now" value={counts.data?.openStops} big />
        <Tile label="Stops completed" value={counts.data?.closedStops} big />
        <Tile label="Average stop" value={average === undefined ? "–" : formatDuration(average)} big foot="completed stops, last 24 h" />
      </Stats>

      <Card
        icon={MapPin}
        title="Stops"
        subtitle="Last 24 hours, newest first."
        live
        flush
        actions={
          <Segmented<Filter>
            label="Filter stops"
            value={filter}
            onChange={setFilter}
            options={[
              ["ALL", "All"],
              ["OPEN", "Standing"],
              ["CLOSED", "Completed"],
            ]}
          />
        }
      >
        {!stops.data ? (
          <Empty>{stops.error ?? "Waiting for the service."}</Empty>
        ) : list.length === 0 ? (
          <Empty>No stops yet.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Company</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th className="right">Duration</th>
                  <th className="right">Readings</th>
                  <th>Place</th>
                </tr>
              </thead>
              <tbody>
                {list.slice(0, 40).map((s) => (
                  <tr key={`${s.imei}-${s.startRecordedAt}`}>
                    <td className="mono">{vehicleName(s.vehicleRegistrationNo, s.imei)}</td>
                    <td className="muted">{s.tenantCode ?? "–"}</td>
                    <td>
                      <Tag tone={s.status === "OPEN" ? "warn" : "neutral"}>{s.status === "OPEN" ? "STANDING" : "DONE"}</Tag>
                    </td>
                    <td className="muted">{formatDateTime(s.startRecordedAt)}</td>
                    <td className="right mono">{formatDuration(s.durationSeconds)}</td>
                    <td className="right mono">{formatNumber(s.readingCount)}</td>
                    <td className="mono faint">{formatCoordinate(s.latitude, s.longitude)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
