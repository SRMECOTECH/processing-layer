"use client";

import { Building2, ChartLine, Route, Truck, Users } from "@/components/icons";
import { Card, Empty, PageHeader } from "@/components/ops/kit";
import { CsvButton } from "@/components/ops/reports";
import { minutes, percent, scoped, type Overview, type Performance } from "@/lib/tms";
import { usePoll } from "@/lib/use-poll";
import { useViewer } from "@/lib/viewer";
import { CompanyPicker, useCompanyScope } from "./shipments";
import s from "./tms.module.css";

/** On-time share as a bar: green from 90 %, amber from 75 %, red below. */
function OnTimeBar({ value }: { value: number | null }) {
  if (value === null) return <span className="faint">–</span>;
  const cls = value >= 90 ? "" : value >= 75 ? s.barMid : s.barLow;
  return (
    <span className="inline-bar">
      <span className={`${s.bar} ${cls}`}>
        <span style={{ width: `${Math.max(2, value)}%` }} />
      </span>
      <span className="mono">{percent(value)}</span>
    </span>
  );
}

function PerformanceCells({ p }: { p: Performance }) {
  return (
    <>
      <td className="right mono">{p.shipments}</td>
      <td className="right mono">{p.active}</td>
      <td className="right mono">{p.delayed > 0 ? <span className="bad">{p.delayed}</span> : 0}</td>
      <td>
        <OnTimeBar value={p.onTimePct} />
      </td>
      <td className="right mono">{p.avgTransitHours === null ? "–" : `${p.avgTransitHours} h`}</td>
      <td className="right mono">{minutes(p.avgDelayMinutesWhenLate)}</td>
    </>
  );
}

const PERFORMANCE_HEAD = (
  <>
    <th className="right">Shipments</th>
    <th className="right">On the road</th>
    <th className="right">Delayed</th>
    <th>On time</th>
    <th className="right">Avg transit</th>
    <th className="right">Late by (avg)</th>
  </>
);

export function TransporterTable({ rows }: { rows: NonNullable<Overview["transporters"]> }) {
  if (rows.length === 0) return <Empty>No shipments in the period.</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Transporter</th>
            <th className="right">Trucks</th>
            {PERFORMANCE_HEAD}
            <th className="right">GPS coverage</th>
            <th className="right">Over-speed</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.code}>
              <td className={s.wrap}>{t.name}</td>
              <td className="right mono">{t.trucks}</td>
              <PerformanceCells p={t} />
              <td className="right mono">{percent(t.gpsCoveragePct)}</td>
              <td className="right mono">{t.overspeedEvents}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function LaneTable({ rows }: { rows: NonNullable<Overview["lanes"]> }) {
  if (rows.length === 0) return <Empty>No shipments in the period.</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Lane</th>
            {PERFORMANCE_HEAD}
          </tr>
        </thead>
        <tbody>
          {rows.map((l) => (
            <tr key={l.lane}>
              <td className={s.wrap}>{l.lane}</td>
              <PerformanceCells p={l} />
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function PlantTable({ rows }: { rows: NonNullable<Overview["plants"]> }) {
  if (rows.length === 0) return <Empty>No load plant in the shipments.</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Load plant</th>
            <th className="right">Shipments</th>
            <th className="right">In the plant fence now</th>
            <th className="right">Works detention</th>
            <th className="right">Hold after gate-out</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p.plant}>
              <td className={s.wrap}>{p.plant}</td>
              <td className="right mono">{p.shipments}</td>
              <td className="right mono">{p.atOriginNow}</td>
              <td className="right mono">{minutes(p.avgWorksDetentionMinutes)}</td>
              <td className="right mono">{minutes(p.avgOriginHoldMinutes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** A consigner's scorecards: transporters, lanes, consignees, plants - the numbers to run the next tender on. */
export function PerformancePage() {
  const viewer = useViewer();
  const poll = usePoll<Overview>(scoped("/api/portal/overview", useCompanyScope()), 30_000);
  const o = poll.data;
  return (
    <div className="stack">
      <PageHeader
        title="Scorecards"
        description={`How each transporter, lane, consignee and plant did over the last ${o?.periodDays ?? 30} days, from the trucks' GPS against the ETAs.`}
        actions={viewer.role === "DEVELOPER" ? <CompanyPicker kinds={["CONSIGNER"]} /> : undefined}
      />
      {!o ? (
        <Empty>{poll.error ?? "Loading."}</Empty>
      ) : (
        <>
          <Card
            icon={Truck}
            title="Transporters"
            flush
            actions={
              <CsvButton
                name="transporter-scorecard"
                rows={() => [
                  ["Transporter", "Trucks", "Shipments", "On the road", "Delayed", "On time %", "Avg transit h", "Late by min", "GPS coverage %", "Over-speed"],
                  ...(o.transporters ?? []).map((t) => [
                    t.name,
                    t.trucks,
                    t.shipments,
                    t.active,
                    t.delayed,
                    t.onTimePct ?? "",
                    t.avgTransitHours ?? "",
                    t.avgDelayMinutesWhenLate ?? "",
                    t.gpsCoveragePct ?? "",
                    t.overspeedEvents,
                  ]),
                ]}
              />
            }
          >
            <TransporterTable rows={o.transporters ?? []} />
          </Card>
          <Card icon={Route} title="Lanes" flush>
            <LaneTable rows={o.lanes ?? []} />
          </Card>
          <div className="grid-2">
            <Card icon={Users} title="Consignees" flush>
              {(o.consignees ?? []).length === 0 ? (
                <Empty>No shipments in the period.</Empty>
              ) : (
                <div className="data-table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Consignee</th>
                        {PERFORMANCE_HEAD}
                      </tr>
                    </thead>
                    <tbody>
                      {(o.consignees ?? []).map((c) => (
                        <tr key={c.consignee}>
                          <td className={s.wrap}>{c.consignee}</td>
                          <PerformanceCells p={c} />
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
            <Card icon={Building2} title="Plants" flush>
              <PlantTable rows={o.plants ?? []} />
            </Card>
          </div>
          <p className={s.small}>
            <ChartLine size={12} /> On time = arrived by the ETA. Transit = first reading outside the plant fence to the arrival. GPS coverage = shipments
            past the gate-out with at least one reading.
          </p>
        </>
      )}
    </div>
  );
}
