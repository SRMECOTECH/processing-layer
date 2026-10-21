"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useDeferredValue, useEffect, useState } from "react";
import { Car, Search } from "lucide-react";
import { Card, Empty, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatCoordinate, formatNumber, formatTime } from "@/lib/format";
import { useLiveVehicles } from "@/lib/live-vehicles";
import { useOverview } from "@/lib/overview-context";

type Filter = "all" | "moving" | "stopped";
const PAGE = 15;
const SPEED_LIMIT = 60;

export default function VehicleListPage() {
  return (
    <Suspense fallback={null}>
      <VehicleList />
    </Suspense>
  );
}

function VehicleList() {
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
  const over = (vehicles ?? []).filter((v) => v.speedKmph > SPEED_LIMIT).length;

  return (
    <div className="stack">
      <PageHeader
        title="Vehicle list"
        description="The latest known position of every vehicle, kept in Redis by the live-location consumer. Each vehicle's frames stay on one Kafka partition, so its readings are always in order."
      />

      <Stats>
        <Tile label="Vehicles with live position" value={o?.vehiclesWithLivePosition} big />
        <Tile label="Devices seen" value={total} big />
        <Tile label="Moving (listed)" value={vehicles ? moving : undefined} unit={vehicles ? `/ ${vehicles.length}` : undefined} big />
        <Tile label={`Over ${SPEED_LIMIT} km/h (listed)`} value={vehicles ? over : undefined} tone={over ? "bad" : undefined} big />
      </Stats>

      <Card
        icon={Car}
        title="Live positions"
        subtitle="gps:live:vehicle:{imei} · overwritten only by a newer reading"
        monoSubtitle
        live
        flush
        actions={
          <Segmented<Filter>
            label="Filter vehicles"
            value={filter}
            onChange={setFilter}
            options={[
              ["all", "All"],
              ["moving", "Moving"],
              ["stopped", "Standing"],
            ]}
          />
        }
      >
        <div className="table-toolbar">
          <label className="search-field">
            <Search size={15} />
            <input
              type="search"
              placeholder="Search by IMEI"
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setLimit(PAGE);
              }}
              aria-label="Search vehicles by IMEI"
            />
          </label>
          {error && <span className="bad">{error}</span>}
        </div>
        {!vehicles ? (
          <Empty>Loading live positions.</Empty>
        ) : shown.length === 0 ? (
          <Empty>No vehicles match.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>IMEI</th>
                  <th>Company</th>
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
                      <Link className="link mono" href={`/live/inspector?imei=${v.imei}`} title="Open in the inspector">
                        {v.registrationNo || "Unassigned"}
                      </Link>
                    </td>
                    <td className="mono muted">{v.imei}</td>
                    <td className="muted">{v.tenantCode || "–"}</td>
                    <td className="mono">
                      {formatTime(v.recordedAt)} <span className="faint">{formatAgo(v.recordedAt)}</span>
                    </td>
                    <td className={`right mono${v.speedKmph > SPEED_LIMIT ? " bad" : ""}`}>{formatNumber(v.speedKmph)} km/h</td>
                    <td>
                      <Tag tone={v.moving ? "good" : "neutral"}>{v.moving ? "MOVING" : "STANDING"}</Tag>
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
            Showing {formatNumber(vehicles?.length)} of {formatNumber(total)} devices
          </span>
          {total !== undefined && limit < total && (
            <button type="button" className="btn soft" onClick={() => setLimit((l) => l + PAGE)}>
              Load {PAGE} more
            </button>
          )}
        </div>
      </Card>
    </div>
  );
}
