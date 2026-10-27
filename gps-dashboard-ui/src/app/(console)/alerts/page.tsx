"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { BellRing, Inbox, Search } from "@/components/icons";
import { EVENT_LABEL, EventTable } from "@/components/ops/events";
import { Card, PageHeader, Segmented, Stats, Tile } from "@/components/ops/kit";
import { alertsFrom } from "@/lib/alerts";
import { useOverview } from "@/lib/overview-context";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Severity = "ALL" | "CRITICAL" | "HIGH" | "MEDIUM";
type Status = "OPEN" | "";
const ALERT_TYPES = ["OVERSPEED", "SPEED_SENSOR_MISMATCH", "STOPPAGE", "IDLING", "HARSH_ACCELERATION", "HARSH_BRAKING"] as const;
const RANK: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1, INFO: 0 };

export default function AllAlertsPage() {
  const { data: overview } = useOverview();
  const platform = alertsFrom(overview).filter((a) => a.tone !== "info");
  const [severity, setSeverity] = useState<Severity>("ALL");
  const [status, setStatus] = useState<Status>("OPEN");
  const [types, setTypes] = useState<Set<string>>(new Set(ALERT_TYPES));
  const [query, setQuery] = useState("");
  const events = usePoll<VehicleEventView[]>(`/api/events?type=${ALERT_TYPES.join(",")}&limit=300${status ? `&status=${status}` : ""}`, 4000);

  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (events.data ?? [])
      .filter((e) => types.has(e.type))
      .filter((e) => (severity === "ALL" ? RANK[e.severity] >= 2 : e.severity === severity))
      .filter((e) => !q || `${e.imei} ${e.vehicleRegistrationNo ?? ""} ${e.tenantCode ?? ""}`.toLowerCase().includes(q))
      .sort((a, b) => RANK[b.severity] - RANK[a.severity] || b.startedAt.localeCompare(a.startedAt));
  }, [events.data, types, severity, query]);

  const all = events.data ?? [];
  const n = (s: string) => all.filter((e) => e.severity === s).length;

  return (
    <div className="stack">
      <PageHeader
        title="All alerts"
        description="Everything that needs an operator: vehicle alerts from the business logic (medium severity and above) and platform problems worked out from the pipeline."
        actions={
          <Link href="/alerts/escalations" className="btn soft">
            Escalation rules
          </Link>
        }
      />

      <Stats>
        <Tile label="Critical" value={events.data ? n("CRITICAL") : undefined} tone={n("CRITICAL") ? "bad" : undefined} big />
        <Tile label="High" value={events.data ? n("HIGH") : undefined} tone={n("HIGH") ? "warn" : undefined} big />
        <Tile label="Medium" value={events.data ? n("MEDIUM") : undefined} big />
        <Tile label="Platform problems" value={overview ? platform.length : undefined} tone={platform.length ? "bad" : "good"} big foot="Kafka, Redis, PostgreSQL, lag" />
      </Stats>

      {platform.length > 0 && (
        <Card icon={BellRing} title="Platform problems" subtitle="Worked out live from the engine overview." live>
          <ul className="row-list">
            {platform.map((a) => (
              <li key={a.title}>
                <Link href={a.href} className="row-link">
                  <span className={`status-dot ${a.tone}`} />
                  <span className="row-main">
                    {a.title}
                    <span className="row-note">{a.text}</span>
                  </span>
                  <span className="row-side faint">Open</span>
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card
        icon={Inbox}
        title="Vehicle alerts"
        subtitle={events.data ? `${list.length} shown, most severe first` : undefined}
        live
        flush
        actions={
          <div className="toolbar-row">
            <Segmented<Severity>
              label="Severity"
              value={severity}
              onChange={setSeverity}
              options={[
                ["ALL", "Medium+"],
                ["CRITICAL", "Critical"],
                ["HIGH", "High"],
                ["MEDIUM", "Medium"],
              ]}
            />
            <Segmented<Status>
              label="Status"
              value={status}
              onChange={setStatus}
              options={[
                ["OPEN", "Ongoing"],
                ["", "All"],
              ]}
            />
          </div>
        }
      >
        <div className="table-toolbar">
          <label className="search-field">
            <Search size={15} />
            <input type="search" placeholder="Vehicle, IMEI or company" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Filter alerts" />
          </label>
          <div className="chip-row">
            {ALERT_TYPES.map((t) => (
              <button
                key={t}
                type="button"
                className="chip"
                aria-pressed={types.has(t)}
                onClick={() =>
                  setTypes((current) => {
                    const next = new Set(current);
                    if (next.has(t)) next.delete(t);
                    else next.add(t);
                    return next;
                  })
                }
              >
                {EVENT_LABEL[t]}
              </button>
            ))}
          </div>
        </div>
        <EventTable events={events.data ? list : undefined} empty="No alerts match. All clear." />
      </Card>
    </div>
  );
}
