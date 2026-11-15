"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useMemo, useState } from "react";
import { Route, Search, TriangleAlert } from "@/components/icons";
import { Card, Empty, PageHeader, Tag } from "@/components/ops/kit";
import { CsvButton } from "@/components/ops/reports";
import { formatDateTime, formatAgo } from "@/lib/format";
import {
  GPS_LINK_LABEL,
  STAGE_LABEL,
  VERDICT_LABEL,
  gpsLinkTone,
  minutes,
  scoped,
  stageTone,
  verdictTone,
  type Company,
  type Shipment,
  type Stage,
} from "@/lib/tms";
import { usePoll } from "@/lib/use-poll";
import { shipmentHref, useViewer } from "@/lib/viewer";
import s from "./tms.module.css";

export function StageTag({ stage }: { stage: Stage }) {
  return <Tag tone={stageTone(stage)}>{STAGE_LABEL[stage]}</Tag>;
}

export function VerdictTag({ shipment }: { shipment: Pick<Shipment, "verdict" | "delayMinutes"> }) {
  const late = shipment.verdict === "LATE" || shipment.verdict === "DELAYED" || shipment.verdict === "AT_RISK";
  return (
    <Tag tone={verdictTone(shipment.verdict)}>
      {VERDICT_LABEL[shipment.verdict]}
      {late && shipment.delayMinutes !== null ? ` · ${minutes(shipment.delayMinutes)}` : ""}
    </Tag>
  );
}

/**
 * The GPS provider's company filters, kept in the URL (?transporter=, ?consigner=): the same page then shows what that
 * transporter or consigner sees. Transporters and consigners never see this - their company is fixed by their sign-in.
 */
export function useCompanyScope(): { transporter?: string; consigner?: string } {
  const params = useSearchParams();
  return { transporter: params.get("transporter") ?? undefined, consigner: params.get("consigner") ?? undefined };
}

export function CompanyPicker({ kinds }: { kinds: ("TRANSPORTER" | "CONSIGNER")[] }) {
  const viewer = useViewer();
  const router = useRouter();
  const params = useSearchParams();
  const companies = usePoll<Company[]>(viewer.role === "DEVELOPER" ? "/api/portal/companies" : null, 60_000);
  if (viewer.role !== "DEVELOPER") return null;
  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params.toString());
    if (value) next.set(key, value);
    else next.delete(key);
    router.replace(`?${next.toString()}`);
  };
  return (
    <div className={s.picker}>
      {kinds.map((kind) => {
        const key = kind === "TRANSPORTER" ? "transporter" : "consigner";
        const list = (companies.data ?? []).filter((c) => c.kind === kind);
        return (
          <select key={kind} className="select" value={params.get(key) ?? ""} onChange={(e) => set(key, e.target.value)} aria-label={`Show as ${key}`}>
            <option value="">{kind === "TRANSPORTER" ? "Every transporter" : "Every consigner"}</option>
            {list.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
                {c.externalCode ? ` (${c.externalCode})` : ""}
              </option>
            ))}
          </select>
        );
      })}
    </div>
  );
}

const STAGE_FILTERS: [string, string][] = [
  ["ACTIVE", "On the road"],
  ["ATTENTION", "Needs attention"],
  ["BOOKED", "Booked"],
  ["ARRIVED", "Arrived"],
  ["CLOSED", "Closed"],
  ["ALL", "All"],
];

function matches(v: Shipment, filter: string): boolean {
  switch (filter) {
    case "ACTIVE":
      return v.stage === "AT_ORIGIN" || v.stage === "IN_TRANSIT";
    case "ATTENTION":
      return v.verdict === "DELAYED" || v.verdict === "AT_RISK" || v.gpsSilent || v.gpsLink === "NO_DEVICE";
    case "ALL":
      return true;
    default:
      return v.stage === filter;
  }
}

/** The shipment list of the viewer's scope: search, a stage filter, the period, CSV. */
export function ShipmentsPage({ title, description }: { title?: string; description?: string }) {
  const viewer = useViewer();
  const scope = useCompanyScope();
  const [q, setQ] = useState("");
  const [days, setDays] = useState(30);
  const [filter, setFilter] = useState("ACTIVE");
  const search = q.trim().length >= 2 ? `&q=${encodeURIComponent(q.trim())}` : "";
  const poll = usePoll<Shipment[]>(scoped(`/api/portal/shipments?days=${days}&limit=2000${search}`, scope), 15_000);
  const rows = useMemo(() => (poll.data ?? []).filter((v) => matches(v, filter)), [poll.data, filter]);
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const [key] of STAGE_FILTERS) c[key] = (poll.data ?? []).filter((v) => matches(v, key)).length;
    return c;
  }, [poll.data]);

  return (
    <div className="stack">
      <PageHeader
        title={title}
        description={
          description ??
          "Every shipment from gate-out to the consignee. Stage and verdict are worked out from the truck's GPS against the plant and destination geofences and the ETA."
        }
        actions={viewer.role === "DEVELOPER" ? <CompanyPicker kinds={["TRANSPORTER", "CONSIGNER"]} /> : undefined}
      />
      <Card
        icon={Route}
        title="Shipments"
        subtitle={poll.data ? `${rows.length} of ${poll.data.length} in the last ${days} days` : "Loading"}
        live={!poll.error}
        flush
        actions={
          <CsvButton
            name="shipments"
            rows={() => [
              ["Trip", "Vehicle", "Transporter", "Consigner unit", "Consignee", "From", "To", "Gate-out", "ETA", "Stage", "Verdict", "Delay min", "Arrived", "GPS"],
              ...rows.map((v) => [
                v.tripNo,
                v.vehicleNo,
                v.transporterName,
                v.consignerUnit ?? "",
                v.consignee ?? "",
                v.origin ?? "",
                v.destination ?? "",
                v.dispatchedAt,
                v.etaAt ?? "",
                v.stage,
                v.verdict,
                v.delayMinutes ?? "",
                v.arrivedAt ?? "",
                v.gpsLink,
              ]),
            ]}
          />
        }
      >
        <div className="table-toolbar">
          <div className="chip-row">
            {STAGE_FILTERS.map(([key, label]) => (
              <button key={key} type="button" className="chip" aria-pressed={filter === key} onClick={() => setFilter(key)}>
                {label} · {counts[key] ?? 0}
              </button>
            ))}
          </div>
          <div className="toolbar-row">
            <label className="search-field">
              <Search size={14} />
              <input placeholder="Trip, vehicle, consignee, invoice, driver" value={q} onChange={(e) => setQ(e.target.value)} />
            </label>
            <select className="select" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="Period">
              <option value={2}>Last 2 days</option>
              <option value={7}>Last 7 days</option>
              <option value={30}>Last 30 days</option>
              <option value={90}>Last 90 days</option>
            </select>
          </div>
        </div>
        {poll.error && !poll.data ? <Empty>{poll.error}</Empty> : <ShipmentTable rows={rows} empty={poll.data ? "No shipment matches." : "Loading shipments."} />}
      </Card>
    </div>
  );
}

export function ShipmentTable({ rows, empty = "No shipments.", compact }: { rows: Shipment[]; empty?: string; compact?: boolean }) {
  const viewer = useViewer();
  if (rows.length === 0) return <Empty>{empty}</Empty>;
  const showTransporter = viewer.role !== "TRANSPORTER";
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Trip</th>
            <th>Vehicle</th>
            {showTransporter && <th>Transporter</th>}
            <th>Route</th>
            {!compact && <th>Consignee</th>}
            <th>Gate-out</th>
            <th>ETA / estimate</th>
            <th>Stage</th>
            <th>Verdict</th>
            <th>GPS</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((v) => (
            <tr key={v.id} className={v.verdict === "DELAYED" ? "flagged" : undefined}>
              <td>
                <Link className="link mono" href={shipmentHref(viewer.role, v.id)}>
                  {v.tripNo}
                </Link>
              </td>
              <td className="mono">{v.vehicleNo}</td>
              {showTransporter && <td className={s.wrap}>{v.transporterName}</td>}
              <td className={s.wrap}>
                {v.origin ?? "–"} → {v.destination ?? "–"}
                {v.consignerUnit && !compact && <div className={s.small}>{v.consignerUnit}</div>}
              </td>
              {!compact && <td className={s.wrap}>{v.consignee ?? "–"}</td>}
              <td className="muted">{formatDateTime(v.dispatchedAt)}</td>
              <td className="muted">
                {v.etaAt ? formatDateTime(v.etaAt) : "–"}
                {v.estimatedArrivalAt && <div className={s.small}>est. {formatDateTime(v.estimatedArrivalAt)}</div>}
                {v.arrivedAt && <div className={s.small}>arrived {formatDateTime(v.arrivedAt)}</div>}
              </td>
              <td>
                <StageTag stage={v.stage} />
              </td>
              <td>
                <VerdictTag shipment={v} />
              </td>
              <td>
                {v.gpsSilent ? (
                  <Tag tone="bad">
                    <TriangleAlert size={11} /> silent {formatAgo(v.lastReadingAt)}
                  </Tag>
                ) : (
                  <Tag tone={gpsLinkTone(v.gpsLink)}>{GPS_LINK_LABEL[v.gpsLink]}</Tag>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
