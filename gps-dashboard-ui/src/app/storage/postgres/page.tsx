"use client";

import { Database, MapPin, Route, Table2, Truck, Users } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Card, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, consumer, isKafkaFlow, storage } from "@/lib/pipeline";

const TABLES: [LucideIcon, string, string][] = [
  [MapPin, "gps_position", "Every stored reading · UNIQUE (imei, recorded_at)"],
  [Route, "gps_trip", "Trips from trip detection · OPEN / CLOSED"],
  [MapPin, "gps_stop", "Stops between trips"],
  [Truck, "vehicle_motion_state", "Per-vehicle state for trip detection"],
  [Truck, "vehicle / device", "Master data: plate and IMEI"],
  [Users, "device_assignment", "Which device was in which vehicle, when"],
];

export default function PostgresSinkPage() {
  const { data: o } = useOverview();
  const stored = storage(o);
  const group = consumer(o, STAGE.positionStorage);
  const trips = consumer(o, STAGE.tripDetection);
  const up = o ? o.storedPositions >= 0 : undefined;

  return (
    <div className="stack">
      <PageHeader
        title="PostgreSQL"
        description="The transactional store (OLTP): every validated position, trips and stops, and the master data that turns an IMEI into a vehicle and a company. Database gps_tracking."
        badges={<Tag tone={up === false ? "bad" : "good"}>{up === false ? "NOT ANSWERING" : "CONNECTED"}</Tag>}
      />

      <Stats>
        <Tile label="Positions stored" value={o && o.storedPositions >= 0 ? o.storedPositions : undefined} big />
        <Tile label="Writes per second" value={stored?.messagesPerSecond} format={formatRate} unit="rows/s" big />
        <Tile label="Storage lag" value={isKafkaFlow(o) ? group?.totalLag : 0} unit="messages" big />
        <Tile label="Failed writes" value={stored?.failures} tone={stored && stored.failures > 0 ? "bad" : undefined} big />
      </Stats>

      <Card
        icon={Database}
        title="PostgreSQL relational sink"
        subtitle="Transactional operational store (OLTP)"
        live
        badges={<Tag mono>Write: {formatRate(stored?.messagesPerSecond)}/s</Tag>}
      >
        <p className="muted" style={{ marginBottom: 16 }}>
          Responsible for operational fleet state: positions, open trips, stops and the device to vehicle history. Tables are
          created by Flyway; Hibernate only checks they match.
        </p>
        <div className="inner-title">Schema manifest · active tables</div>
        <div style={{ display: "grid", gap: 8 }}>
          {TABLES.map(([Icon, name, note]) => (
            <div className="table-line" key={name}>
              <span className="mono">
                <Icon size={14} style={{ verticalAlign: -2, marginRight: 8 }} className="accent-text" />
                {name}
              </span>
              <span className="mono muted" style={{ fontSize: 11.5 }}>
                {note}
              </span>
            </div>
          ))}
        </div>
        <div className="card-foot">
          <span>
            Trip detection writes: <b className="mono">{formatRate(trips?.load.messagesPerSecond)}</b> msg/s · lag{" "}
            <b className="mono">{formatNumber(trips?.totalLag)}</b>
          </span>
          <span className="mono">
            <Table2 size={13} style={{ verticalAlign: -2, marginRight: 4 }} />
            localhost:5432 (Docker 5433)
          </span>
        </div>
      </Card>
    </div>
  );
}
