"use client";

import Link from "next/link";
import { GitCompareArrows, ListChecks, Sigma } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Card, Empty, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader, SettingsCard } from "@/components/ops/module";
import { formatDecimal, formatNumber } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { ActiveSpeedEpisode, EventTypeCount, SpeedRule, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const SPEED = moduleById("speed")!;

export default function CrossCheckPage() {
  const active = usePoll<ActiveSpeedEpisode[]>("/api/speed/active", 3000);
  const rules = usePoll<SpeedRule[]>("/api/speed/rules", 15000);
  const mismatches = usePoll<VehicleEventView[]>("/api/events?type=SPEED_SENSOR_MISMATCH&limit=20", 5000);
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const rule = rules.data?.find((r) => r.scope === "default");
  const checked = (active.data ?? []).filter((a) => a.lastGpsSpeedKmph !== null);
  const agree = checked.filter((a) => a.lastVerdict === "GPS_CONFIRMED").length;
  const sensorHigh = checked.filter((a) => a.lastVerdict === "SENSOR_HIGH").length;

  return (
    <div className="stack">
      <ModuleHeader
        module={SPEED}
        title="Distance cross-check"
        description="The speedometer can be wrong or tampered with, so every reading is checked against the speed the vehicle actually travelled: haversine distance between its last pings divided by the time between them."
      />

      <Stats>
        <Tile label="Episodes cross-checked" value={active.data ? checked.length : undefined} big />
        <Tile label="GPS agrees" value={active.data ? agree : undefined} tone="good" big />
        <Tile label="Speedometer too high" value={active.data ? sensorHigh : undefined} tone={sensorHigh ? "bad" : undefined} big foot="GPS says slower" />
        <Tile label="Mismatch events (24 h)" value={summary.data?.find((s) => s.type === "SPEED_SENSOR_MISMATCH")?.last24Hours ?? (summary.data ? 0 : undefined)} big />
      </Stats>

      <div className="grid-main-side">
        <Card icon={GitCompareArrows} title="Speedometer against travelled speed" subtitle="Vehicles over their limit right now." live flush>
          {!active.data ? (
            <Empty>Waiting for the service.</Empty>
          ) : active.data.length === 0 ? (
            <Empty>No vehicle is over its limit, so nothing is being cross-checked.</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Vehicle</th>
                    <th className="right">Speedometer (avg)</th>
                    <th className="right">From lat/lon</th>
                    <th className="right">Difference</th>
                    <th>Verdict</th>
                  </tr>
                </thead>
                <tbody>
                  {active.data.map((a) => {
                    const diff = a.lastGpsSpeedKmph === null ? null : a.averageSpeedKmph - a.lastGpsSpeedKmph;
                    return (
                      <tr key={a.imei}>
                        <td>
                          <Link className="link mono" href={`/live/inspector?imei=${a.imei}`}>
                            {a.imei}
                          </Link>
                        </td>
                        <td className="right mono">{formatDecimal(a.averageSpeedKmph)} km/h</td>
                        <td className="right mono">{a.lastGpsSpeedKmph === null ? "–" : `${formatDecimal(a.lastGpsSpeedKmph)} km/h`}</td>
                        <td className={`right mono${diff !== null && Math.abs(diff) > (rule?.toleranceKmph ?? 10) ? " bad" : ""}`}>
                          {diff === null ? "–" : `${diff > 0 ? "+" : ""}${formatDecimal(diff)}`}
                        </td>
                        <td>
                          <Tag tone={a.lastVerdict === "GPS_CONFIRMED" ? "good" : a.lastVerdict === "SENSOR_HIGH" ? "bad" : "neutral"}>
                            {a.lastVerdict.replaceAll("_", " ")}
                          </Tag>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card icon={Sigma} title="The formula" subtitle="Worked out over the rule's window of pings.">
          <pre className="code">{`d = 2R · asin(√(sin²(Δφ/2)
      + cos φ1 · cos φ2 · sin²(Δλ/2)))

v_gps = Σd / (t_last − t_first) · 3.6

agree when |v_meter − v_gps|
  ≤ max(tolerance km/h,
        tolerance % · v_meter)`}</pre>
          <p className="muted small">R = 6,378,137 m. Pings further apart than the rule's maximum gap are not used.</p>
        </Card>
      </div>

      <SettingsCard
        title="Verification settings (default rule)"
        subtitle="Each company or vehicle rule can override these."
        source="Redis · gps:config:speed-rule:default"
        live
        settings={[
          { label: "Window", value: rule ? formatNumber(rule.windowPings) : "–", unit: "pings" },
          { label: "Tolerance", value: rule ? formatNumber(rule.toleranceKmph) : "–", unit: "km/h" },
          { label: "Tolerance share", value: rule ? `${Math.round(rule.tolerancePercent * 100)} %` : "–" },
          { label: "Mismatch alert after", value: rule ? formatNumber(rule.mismatchPings) : "–", unit: "pings" },
          { label: "Maximum gap", value: rule ? formatNumber(rule.maxGapSeconds) : "–", unit: "s" },
          { label: "Must agree to count", value: rule ? (rule.requireGpsAgreement ? "yes" : "no") : "–" },
        ]}
        actions={
          <Link href="/processing/speed/rules" className="text-button">
            Edit
          </Link>
        }
      />

      <Card icon={ListChecks} title="Speedometer mismatches" subtitle="The speedometer and the travelled speed disagreed for several pings in a row." live flush>
        <EventTable events={mismatches.data} showType={false} empty="No mismatches: the speedometers agree with GPS." />
      </Card>
    </div>
  );
}
