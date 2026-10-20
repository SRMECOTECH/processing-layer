"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useDeferredValue, useEffect, useState } from "react";
import { Car, MapPinned } from "lucide-react";
import { Card, Empty, PageTitle, Segmented, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatCoordinate, formatNumber, formatTime } from "@/lib/format";
import { useLiveVehicles, type LiveVehicle } from "@/lib/live-vehicles";
import { useOverview } from "@/lib/overview-context";

type Filter = "all" | "moving" | "stopped";
const PAGE = 12;
const SPEED_LIMIT = 60;

export default function VehicleStatePage() {
  return (
    <Suspense fallback={null}>
      <VehicleState />
    </Suspense>
  );
}

function VehicleState() {
  const params = useSearchParams();
  const [query, setQuery] = useState(params.get("imei") ?? "");
  const [limit, setLimit] = useState(PAGE);
  const [filter, setFilter] = useState<Filter>("all");
  const deferred = useDeferredValue(query);
  const { data: o } = useOverview();
  const { vehicles, total, error } = useLiveVehicles(limit, deferred);

  useEffect(() => {
    const imei = params.get("imei");
    if (imei) setQuery(imei);
  }, [params]);

  const shown = (vehicles ?? []).filter((v) => filter === "all" || (filter === "moving" ? v.moving : !v.moving));
  const moving = (vehicles ?? []).filter((v) => v.moving).length;

  return (
    <div className="stack">
      <PageTitle
        title="Vehicle state"
        subtitle="The latest known state of every vehicle, kept in Redis and rebuilt from the Kafka stream. Each vehicle's frames stay on one partition, so its history is always in order."
      />

      <div className="grid-4">
        <Tile label="Vehicles with live state" value={o?.vehiclesWithLivePosition} big />
        <Tile label="Devices seen" value={total} big />
        <Tile label="Moving (shown)" value={vehicles ? moving : undefined} unit={vehicles ? `/ ${vehicles.length}` : undefined} big />
        <Tile label="Speed limit checked" value={SPEED_LIMIT} unit="km/h" big />
      </div>

      <Card
        icon={Car}
        title="Live vehicle state store"
        subtitle="Redis hash gps:live:vehicle:{imei}, overwritten only by a newer reading"
        monoSubtitle
        live
        actions={
          <Segmented<Filter>
            label="Filter vehicles"
            value={filter}
            onChange={setFilter}
            options={[
              ["all", "All"],
              ["moving", "Moving"],
              ["stopped", "Stopped"],
            ]}
          />
        }
      >
        <div style={{ display: "flex", justifyContent: "space-between", gap: 12, flexWrap: "wrap", marginBottom: 16 }}>
          <input
            className="input-box"
            type="search"
            placeholder="Search by IMEI"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setLimit(PAGE);
            }}
            aria-label="Search vehicles by IMEI"
          />
          {error && <span className="bad">{error}</span>}
        </div>
        {!vehicles ? (
          <Empty>Loading live state.</Empty>
        ) : shown.length === 0 ? (
          <Empty>No vehicles match.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>IMEI</th>
                  <th>Last ping</th>
                  <th className="right">Speed</th>
                  <th>State</th>
                  <th>Position</th>
                  <th className="right">Partition</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((v) => (
                  <tr key={v.imei} className={v.speedKmph > SPEED_LIMIT ? "flagged" : undefined}>
                    <td>
                      <span className={`dot ${v.moving ? "" : "muted"}`} />
                      <span className="mono">{v.registrationNo || "Unassigned"}</span>
                    </td>
                    <td className="mono muted">{v.imei}</td>
                    <td className="mono">
                      {formatTime(v.recordedAt)} <span className="faint">{formatAgo(v.recordedAt)}</span>
                    </td>
                    <td className={`right mono${v.speedKmph > SPEED_LIMIT ? " bad" : ""}`}>{formatNumber(v.speedKmph)} km/h</td>
                    <td>
                      <Tag tone={v.moving ? "good" : "neutral"}>{v.moving ? "MOVING" : "STOPPED"}</Tag>
                    </td>
                    <td className="mono muted">{formatCoordinate(v.latitude, v.longitude)}</td>
                    <td className="right mono">p{v.partition}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="card-foot">
          <span>
            Redis live keys: <b className="mono">{formatNumber(o?.vehiclesWithLivePosition)}</b> active · showing{" "}
            {formatNumber(vehicles?.length)} of {formatNumber(total)}
          </span>
          {total !== undefined && limit < total && (
            <button type="button" className="btn soft" onClick={() => setLimit((l) => l + PAGE)}>
              Load {PAGE} more
            </button>
          )}
        </div>
      </Card>

      <Card icon={MapPinned} title="Where these vehicles are" subtitle="Latest positions of the vehicles listed above, to scale." live>
        <PositionMap vehicles={vehicles ?? []} />
      </Card>
    </div>
  );
}

function PositionMap({ vehicles }: { vehicles: LiveVehicle[] }) {
  if (vehicles.length === 0) return <Empty>No positions yet.</Empty>;
  const lats = vehicles.map((v) => v.latitude);
  const lons = vehicles.map((v) => v.longitude);
  const pad = 0.002;
  const minLat = Math.min(...lats) - pad;
  const maxLat = Math.max(...lats) + pad;
  const minLon = Math.min(...lons) - pad;
  const maxLon = Math.max(...lons) + pad;
  const x = (lon: number) => ((lon - minLon) / (maxLon - minLon)) * 960 + 20;
  const y = (lat: number) => 220 - ((lat - minLat) / (maxLat - minLat)) * 200;
  return (
    <svg className="position-map" viewBox="0 0 1000 240" preserveAspectRatio="xMidYMid meet" role="img" aria-label="Vehicle positions">
      {vehicles.map((v) => (
        <g key={v.imei}>
          <title>{`${v.registrationNo || v.imei}: ${formatNumber(v.speedKmph)} km/h`}</title>
          <circle
            cx={x(v.longitude)}
            cy={y(v.latitude)}
            r={v.moving ? 6 : 4.5}
            fill={v.moving ? "var(--accent)" : "var(--text-3)"}
            stroke="var(--surface)"
            strokeWidth={2}
          />
        </g>
      ))}
    </svg>
  );
}
