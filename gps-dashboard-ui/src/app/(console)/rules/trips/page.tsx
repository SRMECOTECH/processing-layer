"use client";

import { useState } from "react";
import { GitFork, Route } from "lucide-react";
import { Card, Empty, KeyValues, PageTitle, Segmented, Tag, Tile } from "@/components/ops/kit";
import { formatDateTime, formatDistance, formatDuration, formatNumber, vehicleName } from "@/lib/format";
import type { TripCounts, TripView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Filter = "ALL" | "OPEN" | "CLOSED";
type Stage = "pre" | "route" | "ingress" | "arrival";
const ROWS = 15;

export default function TripsPage() {
  const trips = usePoll<TripView[]>("/api/trips?limit=500", 5000);
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const [filter, setFilter] = useState<Filter>("ALL");
  const [stage, setStage] = useState<Stage>("route");
  const list = (trips.data ?? []).filter((t) => filter === "ALL" || t.status === filter);
  const total = (counts.data?.openTrips ?? 0) + (counts.data?.closedTrips ?? 0);

  return (
    <div className="stack">
      <PageTitle
        title="Trip detection master"
        subtitle="Trips are opened when a vehicle starts moving and closed after it has stood still long enough; the vehicle and company come from the master tables (device, vehicle, device_assignment)."
      />

      <div className="grid-4">
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
        <Tile label="Vehicles standing" value={counts.data?.openStops} big />
      </div>

      <Card
        icon={Route}
        title="Trips"
        subtitle="Last 24 hours, newest first. Distances come straight from trip detection."
        live
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

      <Card icon={GitFork} title="Trip master engine" subtitle="SYNC: Manifest table v4" monoSubtitle live={false}>
        <div className="field-label" style={{ marginBottom: 8 }}>
          Automated lifecycle detection
        </div>
        <Segmented<Stage>
          label="Trip lifecycle stage"
          value={stage}
          onChange={setStage}
          options={[
            ["pre", "Pre-trip"],
            ["route", "En route"],
            ["ingress", "Ingress"],
            ["arrival", "Arrival"],
          ]}
        />
        <div className="grid-2" style={{ marginTop: 16 }}>
          <div className="inner">
            <KeyValues
              rows={[
                ["Ignition trigger", <span className="mono" key="i">ON (bus pin 14)</span>],
                ["Delta displacement", <span className="mono accent-text" key="d">&gt; 142 m (above 100 m threshold)</span>],
                ["Trip status lock", <span className="mono" key="l">AUTO_COMMITTED</span>],
              ]}
            />
          </div>
          <div className="inner">
            <div className="inner-title">Active dispatch context</div>
            <div className="mono" style={{ fontWeight: 600 }}>
              TRIP-7029-US
            </div>
            <p className="muted" style={{ fontSize: 13, marginTop: 4 }}>
              Truck #44 · Driver: M. Keller
            </p>
            <div style={{ marginTop: 10 }}>
              <Tag mono>410 km remaining</Tag>
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}
