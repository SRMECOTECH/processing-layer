"use client";

import Link from "next/link";
import { Gauge } from "lucide-react";
import { Card, Empty, Stats, Tag, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader } from "@/components/ops/module";
import { formatAgo, formatDecimal, formatNumber } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { ActiveSpeedEpisode, EventTypeCount, SpeedRule } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const SPEED = moduleById("speed")!;

export default function SpeedEpisodesPage() {
  const active = usePoll<ActiveSpeedEpisode[]>("/api/speed/active", 3000);
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const rules = usePoll<SpeedRule[]>("/api/speed/rules", 15000);
  const count = (type: string) => summary.data?.find((s) => s.type === type);
  const confirmed = (active.data ?? []).filter((a) => a.phase === "VIOLATING");
  const candidates = (active.data?.length ?? 0) - confirmed.length;

  return (
    <div className="stack">
      <ModuleHeader
        module={SPEED}
        title="Speed violation · live episodes"
        actions={
          <Link href="/processing/speed/rules" className="btn soft">
            Edit rules
          </Link>
        }
      />

      <Stats>
        <Tile label="Speeding now" value={active.data ? confirmed.length : undefined} tone={confirmed.length ? "bad" : undefined} big foot="confirmed: rule held long enough" />
        <Tile label="Over the limit, not confirmed" value={active.data ? candidates : undefined} tone={candidates ? "warn" : undefined} big foot="still counting pings and seconds" />
        <Tile label="Over-speed events (24 h)" value={count("OVERSPEED")?.last24Hours ?? (summary.data ? 0 : undefined)} big href="/processing/speed/events" />
        <Tile label="Rules in Redis" value={rules.data?.length} big foot="default, company and vehicle" href="/processing/speed/rules" />
      </Stats>

      <Card
        icon={Gauge}
        title="Episodes in progress"
        subtitle="gps:state:speed:{imei} · CANDIDATE becomes VIOLATING once the vehicle's rule is met"
        monoSubtitle
        live
        flush
      >
        {!active.data ? (
          <Empty>Waiting for the service.</Empty>
        ) : active.data.length === 0 ? (
          <Empty>No vehicle is over its limit right now.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Phase</th>
                  <th>Since</th>
                  <th className="right">Pings over</th>
                  <th className="right">Max</th>
                  <th className="right">Average</th>
                  <th className="right">GPS speed</th>
                  <th>Cross-check</th>
                  <th>Rule</th>
                </tr>
              </thead>
              <tbody>
                {active.data.map((a) => (
                  <tr key={a.imei} className={a.phase === "VIOLATING" ? "flagged" : undefined}>
                    <td>
                      <Link className="link mono" href={`/live/inspector?imei=${a.imei}`}>
                        {a.imei}
                      </Link>
                    </td>
                    <td>
                      <Tag tone={a.phase === "VIOLATING" ? "bad" : "warn"}>{a.phase}</Tag>
                    </td>
                    <td className="muted">{formatAgo(a.episodeStartedAt)}</td>
                    <td className="right mono">{formatNumber(a.overLimitReadings)}</td>
                    <td className="right mono bad">{formatNumber(a.maxSpeedKmph)} km/h</td>
                    <td className="right mono">{formatDecimal(a.averageSpeedKmph)} km/h</td>
                    <td className="right mono">{a.lastGpsSpeedKmph === null ? "–" : `${formatDecimal(a.lastGpsSpeedKmph)} km/h`}</td>
                    <td>
                      <Tag tone={a.lastVerdict === "GPS_CONFIRMED" ? "good" : a.lastVerdict === "SENSOR_HIGH" ? "bad" : "neutral"}>
                        {a.lastVerdict.replaceAll("_", " ")}
                      </Tag>
                    </td>
                    <td className="mono muted">
                      {a.ruleScope} · {formatNumber(a.limitKmph)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <HowItWorks module={SPEED} />
    </div>
  );
}
