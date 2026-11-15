"use client";

import { GeofenceDayReportView } from "@/components/geofence/day-report";
import { Callout, PageHeader } from "@/components/ops/kit";
import { PrintButton, PrintHead, ReportFiltersBar, dayLabel, queryString, useReportQuery } from "@/components/ops/reports";
import type { GeofenceDayReport } from "@/lib/geofence";
import type { ReportFilters } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

/** The day-wise geofence report for the GPS provider: every company, or one company or truck. */
export default function GeofenceDayReportPage() {
  const [query, change, ready] = useReportQuery();
  const filters = usePoll<ReportFilters>("/api/reports/filters", 60_000);
  const params = queryString({ date: query.date, tenant: query.tenant, imei: query.imei });
  const { data: r, error } = usePoll<GeofenceDayReport>(ready ? `/api/geofences/report/day?${params}` : null, 30_000);

  return (
    <div className="stack">
      {r && <PrintHead title="Geofence day report" subject={`${dayLabel(r.date)} · ${query.tenant || "all companies"}`} />}
      <PageHeader
        description="Where the day went, fence by fence and truck by truck: how long trucks stayed at plants, customers and yards, which stays were far above the fence's usual, when the gates were crowded, and which trucks are still held."
        meta={[
          ["Day", r ? dayLabel(r.date) : "…"],
          ["Zone", r?.zone ?? "Asia/Kolkata"],
          ["Source", "geofence_visit · module geofence"],
        ]}
      />
      <ReportFiltersBar
        mode="day"
        query={query}
        onChange={change}
        filters={filters.data}
        actions={
          <>
            <a className="btn soft" href={`/api/geofences/report/day.csv?${params}`}>
              Visits CSV
            </a>
            <PrintButton />
          </>
        }
      />
      {error && !r && <Callout tone="warn" title="The report did not load">{error}</Callout>}
      {r && <GeofenceDayReportView report={r} />}
    </div>
  );
}
