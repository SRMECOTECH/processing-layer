"use client";

import { ScanLine } from "@/components/icons";
import { EVENT_LABEL, EventTable } from "@/components/ops/events";
import { Card, PageTitle, Progress, Tile } from "@/components/ops/kit";
import { formatNumber, formatPercent } from "@/lib/format";
import type { EventTypeCount, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function AnomaliesPage() {
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const overspeed = usePoll<VehicleEventView[]>("/api/events?type=OVERSPEED&limit=500", 8000);
  const stoppages = usePoll<VehicleEventView[]>("/api/events?type=STOPPAGE&limit=500", 8000);
  const recent = usePoll<VehicleEventView[]>("/api/events?type=OVERSPEED,SPEED_SENSOR_MISMATCH,HARSH_ACCELERATION,HARSH_BRAKING&limit=30", 5000);

  const verified = (overspeed.data ?? []).filter((e) => e.details.verdict === "GPS_VERIFIED").length;
  const suspect = (overspeed.data ?? []).filter((e) => e.details.verdict === "SENSOR_SUSPECT").length;
  const checked = (stoppages.data ?? []).filter((e) => e.details.contextVerdict && e.details.contextVerdict !== "PENDING");
  const explained = checked.filter((e) => String(e.details.contextVerdict).startsWith("JUSTIFIED")).length;
  const counts = (summary.data ?? []).filter((c) => c.last24Hours > 0).sort((a, b) => b.last24Hours - a.last24Hours);
  const max = Math.max(1, ...counts.map((c) => c.last24Hours));

  return (
    <div className="stack">
      <PageTitle
        title="Violations and anomalies"
        subtitle="How well the checks separate real problems from sensor faults and circumstance: over-speed confirmed by GPS against suspect speedometers, and stoppages explained by traffic or weather against unexplained ones."
      />

      <div className="grid-4">
        <Tile label="Over-speed confirmed by GPS" value={overspeed.data?.length ? (verified / overspeed.data.length) * 100 : 0} format={(v) => v.toFixed(1)} unit="%" tone="accent" big foot={`${verified} of ${overspeed.data?.length ?? 0} episodes`} />
        <Tile label="Suspect speedometers" value={suspect} big foot="GPS contradicted the over-speed" />
        <Tile label="Stoppages explained" value={checked.length ? (explained / checked.length) * 100 : 0} format={(v) => v.toFixed(1)} unit="%" tone="accent" big foot={`${explained} of ${checked.length} checked by the agents`} />
        <Tile label="Harsh driving (24 h)" value={(summary.data?.find((c) => c.type === "HARSH_BRAKING")?.last24Hours ?? 0) + (summary.data?.find((c) => c.type === "HARSH_ACCELERATION")?.last24Hours ?? 0)} big foot="only judged on readings under 10 s apart" />
      </div>

      <Card icon={ScanLine} title="Events by type, last 24 hours" subtitle="vehicle_event, grouped" live>
        <div style={{ display: "grid", gap: 12 }}>
          {counts.map((c) => (
            <div key={c.type}>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <span>{EVENT_LABEL[c.type as VehicleEventView["type"]] ?? c.type}</span>
                <span className="mono">
                  {formatNumber(c.last24Hours)} <span className="faint">({formatNumber(c.openNow)} ongoing)</span>
                </span>
              </div>
              <Progress value={c.last24Hours / max} />
            </div>
          ))}
          {counts.length === 0 && <p className="muted">No events yet.</p>}
        </div>
        <div className="card-foot">
          <span>Share of events that are ongoing</span>
          <span className="mono">
            {formatPercent(counts.reduce((s, c) => s + c.openNow, 0) / Math.max(1, counts.reduce((s, c) => s + c.last24Hours, 0)))}
          </span>
        </div>
      </Card>

      <Card icon={ScanLine} title="Latest speed and driving anomalies" subtitle="Over-speed, speedometer against GPS, harsh acceleration and braking" live>
        <EventTable events={recent.data} />
      </Card>
    </div>
  );
}
