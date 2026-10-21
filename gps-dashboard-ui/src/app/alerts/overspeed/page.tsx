"use client";

import { GitCompareArrows, ListChecks, ShieldCheck } from "lucide-react";
import { CompareChart } from "@/components/ops/charts";
import { EventTable } from "@/components/ops/events";
import { Card, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { formatPercent } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const OVERSPEED = moduleById("overspeed")!;
const METER = [18, 21, 25, 29, 33, 37, 42, 47, 53, 59, 66, 72, 74, 69, 54, 38];
const TRAVELLED = [18, 21, 24, 28, 33, 36, 41, 45, 50, 53, 55, 56, 55, 52, 46, 36];

export default function DualVerificationPage() {
  const events = usePoll<VehicleEventView[]>("/api/events?type=OVERSPEED&limit=300", 5000);
  const all = events.data ?? [];
  const verdict = (v: string) => all.filter((e) => e.details.verdict === v).length;
  const verified = verdict("GPS_VERIFIED");
  const suspect = verdict("SENSOR_SUSPECT");
  const unverified = verdict("UNVERIFIED");

  return (
    <div className="stack">
      <ModuleHeader
        module={OVERSPEED}
        title="Over-speed · dual verification"
        description="An over-speed is only trusted when two independent sources agree: the speed the GPS device logged, and the speed worked out from the distance and time actually travelled. When they disagree, the sensor is suspect instead of the driver."
      />

      <Stats>
        <Tile label="Over-speed episodes" value={events.data ? all.length : undefined} big foot="latest 300" />
        <Tile label="Confirmed by distance" value={events.data ? verified : undefined} tone="good" big foot={all.length ? `${formatPercent(verified / all.length)} of episodes` : undefined} />
        <Tile label="Sensor suspect" value={events.data ? suspect : undefined} tone={suspect ? "bad" : undefined} big foot="device log contradicted" />
        <Tile label="Too few readings" value={events.data ? unverified : undefined} big foot="unverified" />
      </Stats>

      <div className="grid-main-side">
        <Card icon={GitCompareArrows} title="What a sensor fault looks like" subtitle="Device log (solid) against distance ÷ time (dashed), one minute." live={false}>
          <CompareChart primary={METER} secondary={TRAVELLED} limit={60} flagFrom={10} flagTo={13} flagLabel="disagree by > 15 %" limitLabel="limit 60 km/h" height={240} />
          <div className="legend-row">
            <span>
              <i /> Device log
            </span>
            <span>
              <i className="dashed" /> Distance ÷ time
            </span>
            <span className="bad">
              <i className="limit" /> Limit
            </span>
          </div>
        </Card>

        <Card icon={ShieldCheck} title="Verdicts" subtitle="Per episode, from the share of agreeing pings.">
          <ul className="verdicts">
            <li>
              <Tag tone="good">GPS_VERIFIED</Tag>
              <span>Most over-limit pings were confirmed by the travelled speed. Alert the operator.</span>
            </li>
            <li>
              <Tag tone="bad">SENSOR_SUSPECT</Tag>
              <span>The travelled speed mostly contradicted the device. Flag the device, not the driver.</span>
            </li>
            <li>
              <Tag tone="neutral">UNVERIFIED</Tag>
              <span>Too few usable pings (gaps, first readings) to tell either way.</span>
            </li>
          </ul>
        </Card>
      </div>

      <Card icon={ListChecks} title="Over-speed alerts" subtitle="Each with its verdict, newest first." live flush>
        <EventTable events={events.data?.slice(0, 40)} showType={false} empty="No over-speed yet." />
      </Card>
    </div>
  );
}
