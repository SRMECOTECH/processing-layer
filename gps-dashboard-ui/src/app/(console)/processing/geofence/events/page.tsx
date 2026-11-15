"use client";

import { useState } from "react";
import { LogIn } from "@/components/icons";
import { Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { ROLE_LABEL, roleTone, stay, vehicleOf, type Visit } from "@/lib/geofence";
import { formatDateTime } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import { usePoll } from "@/lib/use-poll";

const GEOFENCE = moduleById("geofence")!;
type Kind = "ALL" | "INSIDE" | "VISIT" | "PASS" | "OVER";

/** Every visit and pass as the module writes it (geofence_visit), newest first. */
export default function GeofenceVisitsPage() {
  const [kind, setKind] = useState<Kind>("ALL");
  const { data, error } = usePoll<Visit[]>("/api/geofences/visits?limit=500", 5_000);
  const visits = data ?? [];
  const rows = visits.filter(
    (v) =>
      kind === "ALL" ||
      (kind === "INSIDE" && v.kind === "VISIT" && !v.exitedAt) ||
      (kind === "VISIT" && v.kind === "VISIT") ||
      (kind === "PASS" && v.kind === "PASS") ||
      (kind === "OVER" && v.dwellAlertAt),
  );

  return (
    <div className="stack">
      <ModuleHeader
        module={GEOFENCE}
        title="Entry & exit"
        description="One row per visit and per drive-through. An entry is stamped at the first reading inside and an exit at the last reading inside - times the truck was really seen at; ± shows a GPS gap around the stamp."
      />

      <Stats columns={4}>
        <Tile label="Visits (latest 500)" value={visits.filter((v) => v.kind === "VISIT").length} big />
        <Tile label="Inside now" value={visits.filter((v) => v.kind === "VISIT" && !v.exitedAt).length} tone="good" big />
        <Tile label="Drive-throughs" value={visits.filter((v) => v.kind === "PASS").length} note={`${visits.filter((v) => v.inferred).length} inferred between readings`} big />
        <Tile label="Over the dwell limit" value={visits.filter((v) => v.dwellAlertAt).length} tone="warn" big />
      </Stats>

      <Card
        icon={LogIn}
        title="Visits and passes"
        subtitle="Also published as GEOFENCE_ENTER, GEOFENCE_EXIT, GEOFENCE_PASS and GEOFENCE_DWELL on gps.vehicle-events."
        live={!error}
        flush
        actions={
          <Segmented<Kind>
            label="Show"
            value={kind}
            onChange={setKind}
            options={[
              ["ALL", "All"],
              ["INSIDE", "Inside now"],
              ["VISIT", "Visits"],
              ["PASS", "Passes"],
              ["OVER", "Over limit"],
            ]}
          />
        }
      >
        {rows.length === 0 ? (
          <Empty>{error ?? "Nothing yet: visits appear once a truck has been inside a fence for 15 minutes."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Fence</th>
                  <th>Entered</th>
                  <th>Left</th>
                  <th className="right">Stay</th>
                  <th>What</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.id}>
                    <td className="mono">{vehicleOf(v)}</td>
                    <td>
                      {v.placeName} <Tag tone={roleTone(v.placeRole)}>{ROLE_LABEL[v.placeRole] ?? v.placeRole}</Tag>
                    </td>
                    <td className="mono">
                      {formatDateTime(v.enteredAt)}
                      {(v.entryGapSeconds ?? 0) > 3600 && <span className="faint"> ±{Math.round((v.entryGapSeconds ?? 0) / 60)} min</span>}
                    </td>
                    <td className="mono">
                      {v.exitedAt ? formatDateTime(v.exitedAt) : <span className="faint">still inside</span>}
                      {(v.exitGapSeconds ?? 0) > 3600 && <span className="faint"> ±{Math.round((v.exitGapSeconds ?? 0) / 60)} min</span>}
                    </td>
                    <td className="right mono">{stay(v.minutes)}</td>
                    <td>
                      {v.kind === "PASS" ? (
                        <Tag tone="neutral">{v.inferred ? "Passed (inferred)" : "Passed"}</Tag>
                      ) : v.dwellAlertAt ? (
                        <Tag tone="bad">Over limit</Tag>
                      ) : v.exitedAt ? (
                        <Tag tone="accent">Visit</Tag>
                      ) : (
                        <Tag tone="good">Inside</Tag>
                      )}
                      {v.exitReason === "ESCAPED" && <span className="faint small"> left fast</span>}
                      {v.exitReason === "REPLAY_CUT" && <span className="faint small"> cut by a replay</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
