"use client";

import { DatabaseZap } from "@/components/icons";
import { Card, KeyValues, PageHeader } from "@/components/ops/kit";
import { EmptyDbButton } from "@/components/ops/empty-db";

/** The end-of-day / end-of-month clean-up: print or download the reports, then start again from an empty database. */
export default function ClearDatabasePage() {
  return (
    <div className="stack">
      <PageHeader description="Start again from an empty database, for example after the day's or the month's report has been printed. By default the reports keep their numbers: they are saved before anything is emptied." />

      <Card icon={DatabaseZap} title="Clear database" subtitle="POST /api/admin/reset?keepReports=true" monoSubtitle actions={<EmptyDbButton label="Clear database" />}>
        <KeyValues
          rows={[
            ["Emptied", "Positions, trips, stops, vehicle events, rejected frames and dead letters in PostgreSQL; every message in the Kafka topics; live positions, vehicle states and history in Redis"],
            ["Reports", "Kept by default: each day's numbers are frozen first, so the Day and Month reports still show them. Untick the box to empty them too"],
            ["Kept always", "Companies, vehicles, devices and their assignments; speed rules; cost assumptions; connection settings"],
            ["Takes", "About 6 seconds. The fleet simulator can start again right after"],
          ]}
        />
      </Card>

      <Card title="End of the day or month" subtitle="A good order">
        <ol className="modal-list" style={{ margin: 0 }}>
          <li>Open Reports → Day report (or Month report) and check the numbers.</li>
          <li>Print it (Print / PDF) or download the CSV for Excel.</li>
          <li>Clear the database here, keeping the reports.</li>
        </ol>
      </Card>
    </div>
  );
}
