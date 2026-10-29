"use client";

import { useState } from "react";
import { Route } from "@/components/icons";
import { Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import { formatDateTime, formatDistance, formatDuration, formatNumber, vehicleName } from "@/lib/format";
import type { TripCounts, TripView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Filter = "ALL" | "OPEN" | "CLOSED";
const ROWS = 25;
const TRIPS = moduleById("trips")!;

export default function TripsPage() {
  const trips = usePoll<TripView[]>("/api/trips?limit=500", 5000);
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const [filter, setFilter] = useState<Filter>("ALL");
  const list = (trips.data ?? []).filter((t) => filter === "ALL" || t.status === filter);
  const total = (counts.data?.openTrips ?? 0) + (counts.data?.closedTrips ?? 0);

  return (
    <div className="stack">
      <ModuleHeader
        module={TRIPS}
        title="Trip detection · trips"
        description="A trip opens when a vehicle starts moving and closes after it has stood still long enough. The device's IMEI is looked up in the master tables to find the vehicle and the company it belongs to."
      />

      <Stats>
        <Tile label="Trips in progress" value={counts.data?.openTrips} big tone="accent" />
        <Tile label="Trips completed" value={counts.data?.closedTrips} big />
        <Tile
          label="Completion rate"
          value={total ? (counts.data!.closedTrips / total) * 100 : undefined}
          format={(v) => v.toFixed(1)}
          unit="%"
          big
          progress={total ? counts.data!.closedTrips / total : undefined}
        />
        <Tile label="Vehicles standing" value={counts.data?.openStops} big href="/alerts/trips/stops" />
      </Stats>

      <Card
        icon={Route}
        title="Trips"
        subtitle="Last 24 hours, newest first. Distances come straight from trip detection."
        live
        flush
        actions={
          <Segmented<Filter>
            label="Filter trips"
            value={filter}
            onChange={setFilter}
            options={[
              ["ALL", "All"],
              ["OPEN", "In progress"],
              ["CLOSED", "Completed"],
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
                  <th>Company</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th className="right">Duration</th>
                  <th className="right">Distance</th>
                  <th className="right">Top speed</th>
                </tr>
              </thead>
              <tbody>
                {list.slice(0, ROWS).map((t) => (
                  <tr key={`${t.imei}-${t.startRecordedAt}`}>
                    <td className="mono">{vehicleName(t.vehicleRegistrationNo, t.imei)}</td>
                    <td className="muted">{t.tenantCode ?? "–"}</td>
                    <td>
                      <Tag tone={t.status === "OPEN" ? "good" : "neutral"}>{t.status === "OPEN" ? "EN ROUTE" : "COMPLETED"}</Tag>
                    </td>
                    <td className="muted">{formatDateTime(t.startRecordedAt)}</td>
                    <td className="right mono">{formatDuration(t.durationSeconds)}</td>
                    <td className="right mono">{formatDistance(t.distanceMeters)}</td>
                    <td className="right mono">{formatNumber(t.maxSpeedKmph)} km/h</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="card-foot">
          <span>
            Showing {formatNumber(Math.min(ROWS, list.length))} of {formatNumber(list.length)} trips
          </span>
          <span className="mono">Source: gps_trip</span>
        </div>
      </Card>

    </div>
  );
}
