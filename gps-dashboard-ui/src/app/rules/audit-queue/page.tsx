"use client";

import { ListChecks } from "lucide-react";
import { Card, Empty, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { formatDecimal, formatDistance, formatNumber, formatTime, vehicleName } from "@/lib/format";
import type { GpsPositionView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import { SPEED_TOLERANCE_KMPH, verify, type Verdict } from "@/lib/verification";

const VERDICT_TONE: Record<Verdict, "good" | "bad" | "neutral" | "accent"> = {
  VERIFIED: "good",
  DUAL_FAIL: "bad",
  STOPPED: "neutral",
  FIRST: "accent",
};

const VERDICT_TEXT: Record<Verdict, string> = {
  VERIFIED: "VERIFIED",
  DUAL_FAIL: "DUAL_FAIL",
  STOPPED: "IGN_OFF / STOPPED",
  FIRST: "FIRST READING",
};

export default function AuditQueuePage() {
  const readings = usePoll<GpsPositionView[]>("/api/gps-positions/recently-stored", 1000);
  const rows = (readings.data ?? []).map((r) => ({ reading: r, ...verify(r) }));
  const failed = rows.filter((r) => r.verdict === "DUAL_FAIL").length;
  const verified = rows.filter((r) => r.verdict === "VERIFIED").length;

  return (
    <div className="stack">
      <PageTitle
        title="Ingest audit queue"
        subtitle={`Every stored reading checked twice: the speed the device reported against the speed from distance and time since its previous reading. More than ${SPEED_TOLERANCE_KMPH} km/h apart is a dual failure.`}
      />

      <div className="grid-3">
        <Tile label="Readings in the queue" value={readings.data ? rows.length : undefined} big />
        <Tile label="Verified" value={readings.data ? verified : undefined} tone="good" big />
        <Tile label="Dual failures" value={readings.data ? failed : undefined} tone={failed ? "bad" : undefined} big />
      </div>

      <Card
        icon={ListChecks}
        title="Live ingest verification audit queue"
        subtitle="The last 20 positions written to PostgreSQL. Refreshes every second."
        live
        badges={<Tag mono>AUTO-REFRESH: ON (1s)</Tag>}
      >
        {rows.length === 0 ? (
          <Empty>{readings.error ?? "Waiting for readings."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Timestamp</th>
                  <th>Vehicle</th>
                  <th className="right">Reported speed</th>
                  <th className="right">Calculated</th>
                  <th className="right">Variance</th>
                  <th className="right">Moved</th>
                  <th>Verdict</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(({ reading: r, verdict, calculated, variance }) => (
                  <tr key={`${r.imei}-${r.recordedAt}`} className={verdict === "DUAL_FAIL" ? "flagged" : undefined}>
                    <td className="mono muted">{formatTime(r.recordedAt)}</td>
                    <td className="mono accent-text">{vehicleName(r.vehicleRegistrationNo, r.imei)}</td>
                    <td className="right mono">{formatNumber(r.speedKmph)} km/h</td>
                    <td className="right mono">{calculated === null ? "–" : `${formatDecimal(calculated)} km/h`}</td>
                    <td className={`right mono${verdict === "DUAL_FAIL" ? " bad" : ""}`}>
                      {variance === null ? "–" : `${variance > 0 ? "+" : ""}${formatDecimal(variance)}`}
                    </td>
                    <td className="right mono muted">{formatDistance(r.distanceFromPreviousMeters)}</td>
                    <td>
                      <Tag tone={VERDICT_TONE[verdict]}>{VERDICT_TEXT[verdict]}</Tag>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="card-foot">
          <span>
            Calculated speed = distance from previous reading ÷ time since previous reading. Simulated replays compress time, so
            dual failures here are expected while testing.
          </span>
        </div>
      </Card>
    </div>
  );
}
