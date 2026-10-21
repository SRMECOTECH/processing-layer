"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Car, Search } from "lucide-react";
import { Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader } from "@/components/ops/module";
import { formatAgo, formatDecimal, formatDistance, formatNumber } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import { usePoll } from "@/lib/use-poll";

const STATE = moduleById("state")!;
type Row = Record<string, string>;
type Sort = "recent" | "distance" | "speed";

export default function StateStorePage() {
  const states = usePoll<Row[]>("/api/vehicles/states?limit=500", 4000);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<Sort>("recent");

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = (states.data ?? []).filter((v) => !q || `${v.imei} ${v.vehicleRegistrationNo} ${v.tenantCode}`.toLowerCase().includes(q));
    const by: Record<Sort, (a: Row, b: Row) => number> = {
      recent: (a, b) => String(b.lastRecordedAt).localeCompare(String(a.lastRecordedAt)),
      distance: (a, b) => Number(b.totalDistanceMeters || 0) - Number(a.totalDistanceMeters || 0),
      speed: (a, b) => Number(b.speedKmph || 0) - Number(a.speedKmph || 0),
    };
    return list.sort(by[sort]);
  }, [states.data, query, sort]);

  const all = states.data ?? [];
  const moving = all.filter((v) => v.moving === "true").length;
  const readings = all.reduce((s, v) => s + Number(v.readings || 0), 0);
  const late = all.reduce((s, v) => s + Number(v.outOfOrderReadings || 0), 0);

  return (
    <div className="stack">
      <ModuleHeader module={STATE} title="Vehicle state · state store" />

      <Stats>
        <Tile label="Vehicles with state" value={states.data ? all.length : undefined} big />
        <Tile label="Moving" value={states.data ? moving : undefined} unit={states.data ? `/ ${all.length}` : undefined} tone="good" big />
        <Tile label="Readings folded in" value={states.data ? readings : undefined} big />
        <Tile label="Late readings" value={states.data ? late : undefined} big foot="arrived after a newer one; kept out of the state" />
      </Stats>

      <Card
        icon={Car}
        title="State per vehicle"
        subtitle="gps:state:vehicle:{imei} · one hash per vehicle (Kalman matrices left out)"
        monoSubtitle
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
              ["speed", "Speed"],
            ]}
          />
        }
      >
        <div className="table-toolbar">
          <label className="search-field">
            <Search size={15} />
            <input type="search" placeholder="Vehicle, IMEI or company" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Filter vehicles" />
          </label>
          <span className="faint">{states.data ? `${rows.length} vehicles` : ""}</span>
        </div>
        {!states.data ? (
          <Empty>Waiting for the service.</Empty>
        ) : rows.length === 0 ? (
          <Empty>No vehicle state yet. Start the fleet.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Company</th>
                  <th>State</th>
                  <th className="right">Speed</th>
                  <th className="right">Kalman speed</th>
                  <th className="right">Heading</th>
                  <th className="right">Distance</th>
                  <th className="right">Readings</th>
                  <th>Last reading</th>
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, 60).map((v) => (
                  <tr key={v.imei}>
                    <td>
                      <Link className="link mono" href={`/live/inspector?imei=${v.imei}`}>
                        {v.vehicleRegistrationNo || v.imei}
                      </Link>
                    </td>
                    <td className="muted">{v.tenantCode || "–"}</td>
                    <td>
                      <Tag tone={v.moving === "true" ? "good" : "neutral"}>{v.moving === "true" ? "MOVING" : "STANDING"}</Tag>
                    </td>
                    <td className="right mono">{formatNumber(Number(v.speedKmph || 0))} km/h</td>
                    <td className="right mono muted">{formatDecimal(Number(v.kalmanSpeedKmph || 0))} km/h</td>
                    <td className="right mono muted">{v.headingDegrees ? `${Math.round(Number(v.headingDegrees))}°` : "–"}</td>
                    <td className="right mono">{formatDistance(Number(v.totalDistanceMeters || 0))}</td>
                    <td className="right mono">{formatNumber(Number(v.readings || 0))}</td>
                    <td className="muted">{formatAgo(v.lastRecordedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <HowItWorks module={STATE} />
    </div>
  );
}
