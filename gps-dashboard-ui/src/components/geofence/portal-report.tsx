"use client";

import { useEffect, useState } from "react";
import { GeofenceDayReportView } from "@/components/geofence/day-report";
import { Callout, PageHeader } from "@/components/ops/kit";
import { PrintButton, PrintHead, dayLabel, queryString, shiftDay, todayIn } from "@/components/ops/reports";
import type { GeofenceDayReport } from "@/lib/geofence";
import { usePoll } from "@/lib/use-poll";

/**
 * The geofence day report in a transporter's or consigner's portal: the service cuts it to the user's own trucks
 * (GET /api/portal/geofence-report), whatever the page asks for.
 */
export function PortalGeofenceReport({ description }: { description: string }) {
  const [date, setDate] = useState("");
  useEffect(() => {
    setDate(new URLSearchParams(window.location.search).get("date") ?? todayIn());
  }, []);
  function pick(day: string) {
    setDate(day);
    window.history.replaceState(null, "", `${window.location.pathname}?date=${day}`);
  }
  const params = queryString({ date });
  const { data: r, error } = usePoll<GeofenceDayReport>(date ? `/api/portal/geofence-report?${params}` : null, 30_000);
  const today = todayIn();

  return (
    <div className="stack">
      {r && <PrintHead title="Geofence day report" subject={dayLabel(r.date)} />}
      <PageHeader description={description} meta={[["Day", date ? dayLabel(date) : "…"], ["Zone", r?.zone ?? "Asia/Kolkata"]]} />
      <div className="toolbar-row">
        <input className="input-box" type="date" value={date} max={today} onChange={(e) => e.target.value && pick(e.target.value)} aria-label="Day" />
        <button type="button" className="btn soft" onClick={() => pick(today)}>
          Today
        </button>
        <button type="button" className="btn soft" onClick={() => pick(shiftDay(today, -1))}>
          Yesterday
        </button>
        <button type="button" className="btn soft" aria-label="Day before" onClick={() => date && pick(shiftDay(date, -1))}>
          ‹
        </button>
        <button type="button" className="btn soft" aria-label="Day after" disabled={!date || date >= today} onClick={() => pick(shiftDay(date, 1))}>
          ›
        </button>
        <a className="btn soft" href={`/api/portal/geofence-report.csv?${params}`}>
          Visits CSV
        </a>
        <PrintButton />
      </div>
      {error && !r && <Callout tone="warn" title="The report did not load">{error}</Callout>}
      {r && <GeofenceDayReportView report={r} />}
    </div>
  );
}
