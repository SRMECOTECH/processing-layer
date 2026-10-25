"use client";

import Link from "next/link";
import { useState } from "react";
import { Gauge, Map as MapIcon } from "lucide-react";
import { Card, Empty, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { PositionMap } from "@/components/ops/position-map";
import { formatAgo, formatNumber } from "@/lib/format";
import { useLiveVehicles } from "@/lib/live-vehicles";

type Filter = "all" | "moving" | "stopped";
type Size = "40" | "80" | "150";

export default function LiveMapPage() {
  const [filter, setFilter] = useState<Filter>("all");
  const [size, setSize] = useState<Size>("80");
  const { vehicles, total, error } = useLiveVehicles(Number(size), "", 4000);
  const shown = (vehicles ?? []).filter((v) => filter === "all" || (filter === "moving" ? v.moving : !v.moving));
  const moving = (vehicles ?? []).filter((v) => v.moving).length;
  const fastest = [...(vehicles ?? [])].sort((a, b) => b.speedKmph - a.speedKmph).slice(0, 6);

  return (
    <div className="stack">
      <PageHeader
        title="Live map"
        description="Latest position of each vehicle from Redis (gps:live:vehicle:*), drawn to scale. Click a point to open the vehicle in the inspector."
        actions={
          <Segmented<Size>
            label="Vehicles on the map"
            value={size}
            onChange={setSize}
            options={[
              ["40", "40"],
              ["80", "80"],
              ["150", "150"],
            ]}
          />
        }
      />

      <Stats>
        <Tile label="On the map" value={vehicles?.length} unit={total !== undefined ? `of ${formatNumber(total)} devices` : undefined} big />
        <Tile label="Moving" value={vehicles ? moving : undefined} tone="good" big />
        <Tile label="Standing" value={vehicles ? vehicles.length - moving : undefined} big />
        <Tile label="Fastest now" value={fastest[0]?.speedKmph} unit="km/h" big foot={fastest[0] ? fastest[0].registrationNo || fastest[0].imei : undefined} />
      </Stats>

      <div className="grid-main-side">
        <Card
          icon={MapIcon}
          title="Fleet positions"
          subtitle={error ?? "Refreshes every 4 seconds."}
          live
          actions={
            <Segmented<Filter>
              label="Show"
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
          {!vehicles ? <Empty>Loading positions.</Empty> : <PositionMap vehicles={shown} height={460} />}
        </Card>

        <Card icon={Gauge} title="Fastest vehicles" subtitle="Speedometer of the latest reading." live>
          {fastest.length === 0 ? (
            <Empty>No vehicles yet.</Empty>
          ) : (
            <ul className="row-list">
              {fastest.map((v) => (
                <li key={v.imei}>
                  <Link href={`/live/inspector?imei=${v.imei}`} className="row-link">
                    <span className={`status-dot ${v.moving ? "good" : "muted"}`} />
                    <span className="row-main mono">{v.registrationNo || v.imei}</span>
                    <span className="row-side mono">
                      {v.speedKmph > 60 ? <Tag tone="bad">{formatNumber(v.speedKmph)} km/h</Tag> : `${formatNumber(v.speedKmph)} km/h`}
                    </span>
                  </Link>
                  <div className="row-note">{formatAgo(v.recordedAt)}</div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}
