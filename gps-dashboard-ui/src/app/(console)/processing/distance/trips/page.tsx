"use client";

import { useState } from "react";
import { Route } from "@/components/icons";
import { Card, Empty, Progress, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { formatDateTime, formatDecimal, formatDistance, formatDuration, formatNumber, vehicleName } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { TripView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const DISTANCE = moduleById("distance")!;
type Filter = "ALL" | "OPEN" | "CLOSED";

export default function TripIncrementsPage() {
  const trips = usePoll<TripView[]>("/api/trips?limit=500", 5000);
  const [filter, setFilter] = useState<Filter>("OPEN");
  const all = trips.data ?? [];
  const list = all.filter((t) => filter === "ALL" || t.status === filter).sort((a, b) => b.distanceMeters - a.distanceMeters);
  const total = all.reduce((s, t) => s + t.distanceMeters, 0);
  const open = all.filter((t) => t.status === "OPEN");
  const growing = open.reduce((s, t) => s + t.distanceMeters, 0);
  const max = Math.max(1, ...list.map((t) => t.distanceMeters));

  return (
    <div className="stack">
      <ModuleHeader
        module={DISTANCE}
        title="Trip increments"
        description="Every reading adds its filtered step to the vehicle's open trip, so a trip's distance grows while it is driven and stops growing when the vehicle parks."
      />

      <Stats>
        <Tile label="Distance, last 24 h" value={trips.data ? formatDistance(total) : undefined} big />
        <Tile label="Trips growing now" value={trips.data ? open.length : undefined} tone="good" big />
        <Tile label="Distance on open trips" value={trips.data ? formatDistance(growing) : undefined} big />
        <Tile label="Late readings" value={trips.data ? all.reduce((s, t) => s + t.lateReadingCount, 0) : undefined} big foot="arrived after the trip moved on" />
      </Stats>

      <Card
        icon={Route}
        title="Distance per trip"
        subtitle="gps_trip.distance_meters, longest first."
        live
        flush
        actions={
          <Segmented<Filter>
            label="Trips"
            value={filter}
            onChange={setFilter}
            options={[
              ["OPEN", "Growing"],
              ["CLOSED", "Completed"],
              ["ALL", "All"],
            ]}
          />
        }
      >
        {!trips.data ? (
          <Empty>{trips.error ?? "Waiting for the service."}</Empty>
        ) : list.length === 0 ? (
          <Empty>No trips yet.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th className="right">Duration</th>
                  <th style={{ width: "28%" }}>Distance</th>
                  <th className="right">Average</th>
                  <th className="right">Readings</th>
                </tr>
              </thead>
              <tbody>
                {list.slice(0, 25).map((t) => (
                  <tr key={`${t.imei}-${t.startRecordedAt}`}>
                    <td className="mono">{vehicleName(t.vehicleRegistrationNo, t.imei)}</td>
                    <td>
                      <Tag tone={t.status === "OPEN" ? "good" : "neutral"}>{t.status === "OPEN" ? "GROWING" : "DONE"}</Tag>
                    </td>
                    <td className="muted">{formatDateTime(t.startRecordedAt)}</td>
                    <td className="right mono">{formatDuration(t.durationSeconds)}</td>
                    <td>
                      <div className="inline-bar">
                        <Progress value={t.distanceMeters / max} />
                        <span className="mono">{formatDistance(t.distanceMeters)}</span>
                      </div>
                    </td>
                    <td className="right mono">{formatDecimal(t.averageSpeedKmph)} km/h</td>
                    <td className="right mono">{formatNumber(t.readingCount)}</td>
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
