"use client";

import Link from "next/link";
import { Fragment, useEffect, useMemo, useState, type ReactNode } from "react";
import { ChevronRight, FileText, Info, RotateCcw } from "@/components/icons";
import { KeyValues } from "@/components/ops/kit";
import type { FuelAssumptions, ReportFilters, ReportRow } from "@/lib/types";
import s from "./reports.module.css";

// ---------------------------------------------------------------- numbers in words a manager reads

const INR = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const ONE = new Intl.NumberFormat("en-IN", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const TWO = new Intl.NumberFormat("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const WHOLE = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

export const rupees = (value: number | null | undefined) => INR.format(Math.round(value ?? 0));
export const litres = (value: number | null | undefined) => `${ONE.format(value ?? 0)} L`;
export const kms = (value: number | null | undefined) => `${ONE.format(value ?? 0)} km`;
export const kilos = (value: number | null | undefined) => `${ONE.format(value ?? 0)} kg`;
export const whole = (value: number | null | undefined) => WHOLE.format(value ?? 0);
export const hours = (seconds: number | null | undefined) => `${TWO.format((seconds ?? 0) / 3600)} h`;

/** "3 h 05 min", "42 min". */
export function duration(seconds: number | null | undefined): string {
  const total = Math.max(0, Math.round((seconds ?? 0) / 60));
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h === 0 ? `${m} min` : `${h} h ${String(m).padStart(2, "0")} min`;
}

/** The time of day where the report's days are counted (IST by default). */
export function clock(iso: string | null | undefined, zone: string): string {
  if (!iso) return "–";
  return new Date(iso).toLocaleTimeString("en-GB", { timeZone: zone, hour: "2-digit", minute: "2-digit", hour12: false });
}

export function dayLabel(day: string): string {
  return new Date(`${day}T12:00:00Z`).toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short", year: "numeric" });
}

export function monthLabel(month: string): string {
  return new Date(`${month}-15T12:00:00Z`).toLocaleDateString("en-GB", { month: "long", year: "numeric" });
}

export function vehicleLabel(row: { registration: string | null; imei: string | null }): string {
  return row.registration ?? row.imei ?? "Fleet";
}

/** Today in the reports' zone, as yyyy-mm-dd (the browser may be elsewhere). */
export function todayIn(zone = "Asia/Kolkata"): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

export function shiftDay(day: string, days: number): string {
  const d = new Date(`${day}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function shiftMonth(month: string, months: number): string {
  const d = new Date(`${month}-15T12:00:00Z`);
  d.setUTCMonth(d.getUTCMonth() + months);
  return d.toISOString().slice(0, 7);
}

// ---------------------------------------------------------------- the query, kept in the address bar

export type ReportQuery = { date: string; month: string; tenant: string; imei: string };

/**
 * The report's filters, mirrored in the address (?date=&month=&tenant=&imei=) so a report can be bookmarked, shared
 * or printed again. `ready` turns true once the address has been read.
 */
export function useReportQuery(): [ReportQuery, (change: Partial<ReportQuery>) => void, boolean] {
  const [query, setQuery] = useState<ReportQuery>({ date: "", month: "", tenant: "", imei: "" });
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const p = new URLSearchParams(window.location.search);
    const today = todayIn();
    setQuery({
      date: p.get("date") ?? today,
      month: p.get("month") ?? today.slice(0, 7),
      tenant: p.get("tenant") ?? "",
      imei: p.get("imei") ?? "",
    });
    setReady(true);
  }, []);

  function change(next: Partial<ReportQuery>) {
    setQuery((current) => {
      const merged = { ...current, ...next };
      const p = new URLSearchParams();
      Object.entries(merged).forEach(([key, value]) => value && p.set(key, value));
      window.history.replaceState(null, "", `${window.location.pathname}?${p.toString()}`);
      return merged;
    });
  }

  return [query, change, ready];
}

export function queryString(params: Record<string, string>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => value && p.set(key, value));
  return p.toString();
}

// ---------------------------------------------------------------- filters

type FilterProps = {
  mode: "day" | "month" | "vehicle";
  query: ReportQuery;
  onChange: (change: Partial<ReportQuery>) => void;
  filters: ReportFilters | undefined;
  actions?: ReactNode;
};

/** Date or month, company and vehicle drop-downs; a vehicle day sheet needs a vehicle. */
export function ReportFiltersBar({ mode, query, onChange, filters, actions }: FilterProps) {
  const today = filters?.today ?? todayIn();
  const vehicles = useMemo(
    () => (filters?.vehicles ?? []).filter((v) => !query.tenant || v.tenant === query.tenant),
    [filters, query.tenant],
  );

  return (
    <div className={s.filters}>
      {mode !== "month" ? (
        <label className={s.filter}>
          <span className="field-label">Day</span>
          <input className="input-box" type="date" value={query.date} max={today} onChange={(e) => e.target.value && onChange({ date: e.target.value })} />
        </label>
      ) : (
        <label className={s.filter}>
          <span className="field-label">Month</span>
          <input className="input-box" type="month" value={query.month} max={today.slice(0, 7)} onChange={(e) => e.target.value && onChange({ month: e.target.value })} />
        </label>
      )}
      <div className={s.quick}>
        {mode !== "month" ? (
          <>
            <button type="button" className="btn soft" onClick={() => onChange({ date: today })}>
              Today
            </button>
            <button type="button" className="btn soft" onClick={() => onChange({ date: shiftDay(today, -1) })}>
              Yesterday
            </button>
            <button type="button" className="btn soft" aria-label="Day before" onClick={() => onChange({ date: shiftDay(query.date || today, -1) })}>
              ‹
            </button>
            <button
              type="button"
              className="btn soft"
              aria-label="Day after"
              disabled={(query.date || today) >= today}
              onClick={() => onChange({ date: shiftDay(query.date || today, 1) })}
            >
              ›
            </button>
          </>
        ) : (
          <>
            <button type="button" className="btn soft" onClick={() => onChange({ month: today.slice(0, 7) })}>
              This month
            </button>
            <button type="button" className="btn soft" onClick={() => onChange({ month: shiftMonth(today.slice(0, 7), -1) })}>
              Last month
            </button>
          </>
        )}
      </div>
      <label className={`${s.filter} ${s.filterWide}`}>
        <span className="field-label">Company</span>
        <select className="select" value={query.tenant} onChange={(e) => onChange({ tenant: e.target.value, imei: "" })}>
          <option value="">All companies ({filters?.tenants.length ?? 0})</option>
          {(filters?.tenants ?? []).map((t) => (
            <option key={t.code} value={t.code}>
              {t.name}
            </option>
          ))}
        </select>
      </label>
      <label className={`${s.filter} ${s.filterWide}`}>
        <span className="field-label">Vehicle</span>
        <select className="select" value={query.imei} onChange={(e) => onChange({ imei: e.target.value })}>
          <option value="">{mode === "vehicle" ? "Choose a vehicle" : `All vehicles (${vehicles.length})`}</option>
          {vehicles.map((v) => (
            <option key={v.imei} value={v.imei}>
              {v.registration ?? v.imei}
              {v.tenant ? ` · ${v.tenant}` : ""}
            </option>
          ))}
        </select>
      </label>
      {actions && <div className={s.actions}>{actions}</div>}
    </div>
  );
}

export function PrintButton() {
  return (
    <button type="button" className="btn soft" onClick={() => window.print()}>
      <FileText size={15} />
      Print / PDF
    </button>
  );
}

export function CsvButton({ name, rows }: { name: string; rows: () => (string | number)[][] }) {
  return (
    <button type="button" className="btn soft" onClick={() => downloadCsv(name, rows())}>
      Download CSV
    </button>
  );
}

export function RefreshButton({ onClick, busy }: { onClick: () => void; busy: boolean }) {
  return (
    <button type="button" className="btn soft" onClick={onClick} disabled={busy} title="Summaries are rebuilt every 5 minutes; this rebuilds the day now">
      <RotateCcw size={15} />
      {busy ? "Rebuilding" : "Rebuild now"}
    </button>
  );
}

/** Opens in Excel; numbers stay numbers (no thousands separators). */
export function downloadCsv(name: string, rows: (string | number)[][]) {
  const text = rows
    .map((row) => row.map((cell) => (typeof cell === "number" ? String(Math.round(cell * 100) / 100) : `"${String(cell).replace(/"/g, '""')}"`)).join(","))
    .join("\r\n");
  const url = URL.createObjectURL(new Blob(["﻿" + text], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** The heading only paper shows: what the report is, for what, and when it was printed. */
export function PrintHead({ title, subject }: { title: string; subject: string }) {
  return (
    <div className={s.printHead}>
      <strong>PulseStream · {title}</strong> · {subject} · printed {new Date().toLocaleString("en-GB")}
    </div>
  );
}

// ---------------------------------------------------------------- "How is this calculated?"

export function Explain({ title = "How is this calculated?", children }: { title?: string; children: ReactNode }) {
  return (
    <details className={s.explain}>
      <summary>
        <Info size={13} />
        {title}
      </summary>
      <div className={s.explainBody}>{children}</div>
    </details>
  );
}

/** One line of a calculation: what, how (with the real numbers), result. */
export function Formula({ label, how, result }: { label: string; how: ReactNode; result: ReactNode }) {
  return (
    <div className={s.formula}>
      <span className={s.fLabel}>{label}</span>
      <span className={s.fExpr}>{how}</span>
      <span className={s.fResult}>{result}</span>
    </div>
  );
}

export function ExplainNote({ children }: { children: ReactNode }) {
  return <p className={s.note}>{children}</p>;
}

/** Every step from what the vehicle did to litres, rupees and CO2, with this row's numbers. */
export function FuelSteps({ row, a }: { row: ReportRow; a: FuelAssumptions }) {
  const f = row.fuel;
  const idleHours = row.idleSeconds / 3600;
  return (
    <>
      <Formula label="Driving fuel" how={`${ONE.format(row.distanceKm)} km ÷ ${a.mileageKmPerLitre} km/L`} result={litres(f.drivingLitres)} />
      <Formula label="Idling fuel" how={`${TWO.format(idleHours)} h idling × ${a.idleLitresPerHour} L/h`} result={litres(f.idleLitres)} />
      <Formula
        label="Fast-driving extra"
        how={`${ONE.format(row.fastKm)} km above ${a.economicalSpeedKmph} km/h ÷ ${a.mileageKmPerLitre} km/L × ${a.fastDrivingPenaltyPercent} %`}
        result={litres(f.fastLitres)}
      />
      <Formula label="Fuel used" how={`${ONE.format(f.drivingLitres)} + ${ONE.format(f.idleLitres)} + ${ONE.format(f.fastLitres)} L`} result={litres(f.usedLitres)} />
      <Formula label="Cost" how={`${ONE.format(f.usedLitres)} L × ₹${a.pricePerLitre}/L`} result={rupees(f.cost)} />
      <Formula
        label="Could be saved"
        how={`(${ONE.format(f.idleLitres)} idling + ${ONE.format(f.fastLitres)} fast) L × ₹${a.pricePerLitre}/L`}
        result={<span className={s.save}>{rupees(f.savableCost)}</span>}
      />
      <Formula label="CO₂" how={`${ONE.format(f.usedLitres)} L × ${a.co2KgPerLitre} kg/L`} result={kilos(f.co2Kg)} />
    </>
  );
}

/** What each column of the vehicle table means and where it comes from. */
export function ColumnGuide({ a }: { a: FuelAssumptions }) {
  return (
    <Explain title="How every column is calculated">
      <Formula label="Distance" how="sum of the distance between consecutive readings (GPS, jitter and jumps filtered)" result="km" />
      <Formula label="Driving" how="time between readings while the vehicle was MOVING (a gap over 10 min is not counted)" result="h min" />
      <Formula label="Standing" how="time between readings while the vehicle was STOPPED (gaps up to 1 h: a parked tracker reports rarely)" result="h min" />
      <Formula label="Stops" how="stops detected that day: within 200 m for at least 3 min (trip detector)" result="count" />
      <Formula
        label="Idling"
        how="engine on while standing past 5 min (IDLING episodes, overlaps counted once) between the day's first and last reading; never more than Standing"
        result="h min"
      />
      <Formula label="Max speed" how="highest speed reported by the device that day" result="km/h" />
      <Formula label="Over-speed" how="over-speed episodes that started that day (speed rules of Processing → Speed violation)" result="count" />
      <Formula label="Fuel" how={`distance ÷ ${a.mileageKmPerLitre} km/L + idling h × ${a.idleLitresPerHour} L/h + fast-driving extra`} result="L" />
      <Formula label="Cost" how={`fuel × ₹${a.pricePerLitre}/L (${a.fuelType})`} result="₹" />
      <Formula label="Could save" how="(idling fuel + fast-driving extra) × price: what switching off and steadier driving save" result="₹" />
      <ExplainNote>
        Fuel is an estimate from the assumptions on Reports → Cost assumptions (no fuel sensor is connected). Change them there and every report
        is priced again at once.
      </ExplainNote>
    </Explain>
  );
}

// ---------------------------------------------------------------- the vehicle table

type TableProps = {
  rows: ReportRow[];
  totals: ReportRow;
  a: FuelAssumptions;
  zone: string;
  /** day report: a link to the vehicle's day sheet */
  day?: string;
  /** month report: show the days with readings */
  showDays?: boolean;
};

/** One row per vehicle; a click opens the row's full breakdown. The fleet total is the last row. */
export function VehicleTable({ rows, totals, a, zone, day, showDays }: TableProps) {
  const [open, setOpen] = useState<string>();
  const columns = 12 + (showDays ? 1 : 0);

  return (
    <div className="data-table-wrap">
      <table className={s.table}>
        <thead>
          <tr>
            <th aria-label="Open" />
            <th>Vehicle</th>
            {showDays && <th>Days</th>}
            <th>Distance</th>
            <th>Driving</th>
            <th>Standing</th>
            <th>Stops</th>
            <th>Idling</th>
            <th>Max speed</th>
            <th>Over-speed</th>
            <th>Fuel</th>
            <th>Cost</th>
            <th>Could save</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = row.imei ?? "";
            const expanded = open === key;
            return (
              <Fragment key={key}>
                <tr className={s.row} aria-expanded={expanded} onClick={() => setOpen(expanded ? undefined : key)}>
                  <td>
                    <button type="button" className={s.toggle} aria-label={`${expanded ? "Hide" : "Show"} the breakdown of ${vehicleLabel(row)}`}>
                      <ChevronRight size={14} />
                    </button>
                  </td>
                  <td>
                    <span className={s.vehicle}>
                      <span className={s.vehicleName}>{vehicleLabel(row)}</span>
                      <span className={s.vehicleSub} title={`IMEI ${row.imei}`}>
                        {row.tenant ?? "–"}
                      </span>
                    </span>
                  </td>
                  {showDays && <td>{row.days}</td>}
                  <td>{kms(row.distanceKm)}</td>
                  <td>{duration(row.movingSeconds)}</td>
                  <td>{duration(row.stoppedSeconds)}</td>
                  <td>{row.stops}</td>
                  <td className={row.idleSeconds >= 3600 ? s.warnText : undefined}>{duration(row.idleSeconds)}</td>
                  <td className={row.maxSpeedKmph > row.economicalSpeedKmph + 20 ? s.warnText : undefined}>{row.maxSpeedKmph} km/h</td>
                  <td>{row.overspeedEvents}</td>
                  <td>{litres(row.fuel.usedLitres)}</td>
                  <td className={s.money}>{rupees(row.fuel.cost)}</td>
                  <td className={s.save}>{rupees(row.fuel.savableCost)}</td>
                </tr>
                {expanded && (
                  <tr className={s.detail}>
                    <td colSpan={columns}>
                      <VehicleBreakdown row={row} a={a} zone={zone} day={day} />
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
          {rows.length > 1 && (
            <tr className={s.totals}>
              <td />
              <td>Fleet · {rows.length} vehicles</td>
              {showDays && <td>{totals.days}</td>}
              <td>{kms(totals.distanceKm)}</td>
              <td>{duration(totals.movingSeconds)}</td>
              <td>{duration(totals.stoppedSeconds)}</td>
              <td>{totals.stops}</td>
              <td>{duration(totals.idleSeconds)}</td>
              <td>{totals.maxSpeedKmph} km/h</td>
              <td>{totals.overspeedEvents}</td>
              <td>{litres(totals.fuel.usedLitres)}</td>
              <td className={s.money}>{rupees(totals.fuel.cost)}</td>
              <td className={s.save}>{rupees(totals.fuel.savableCost)}</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

/** Under a vehicle's row: what it did, and how its litres and rupees were worked out. */
export function VehicleBreakdown({ row, a, zone, day }: { row: ReportRow; a: FuelAssumptions; zone: string; day?: string }) {
  return (
    <div className={s.detailGrid}>
      <div>
        <h3 className={s.detailTitle}>What it did</h3>
        <KeyValues
          rows={[
            ["Device (IMEI)", <span className="mono">{row.imei}</span>],
            ["First / last reading", `${clock(row.firstSeen, zone)} – ${clock(row.lastSeen, zone)}${row.days > 1 ? ` (over ${row.days} days)` : ""}`],
            ["Readings", whole(row.readings)],
            ["Trips", row.trips],
            ["Time in stops", `${duration(row.stopSeconds)} in ${row.stops} stops (${row.engineOnStops} with the engine on)`],
            ["Average driving speed", `${ONE.format(row.avgMovingSpeedKmph)} km/h = ${ONE.format(row.distanceKm)} km ÷ ${hours(row.movingSeconds)}`],
            ["Utilisation", `${ONE.format(row.utilisationPercent)} % = driving ÷ (driving + standing)`],
            [`Above ${row.economicalSpeedKmph || a.economicalSpeedKmph} km/h`, kms(row.fastKm)],
            ["Harsh braking / acceleration", row.harshEvents],
          ]}
        />
        {day && row.imei && (
          <p style={{ marginTop: 12 }}>
            <Link className="btn soft" href={`/reports/vehicle?${queryString({ date: day, imei: row.imei })}`}>
              Open its day sheet: every stop and trip
            </Link>
          </p>
        )}
      </div>
      <div>
        <h3 className={s.detailTitle}>Fuel and money, step by step</h3>
        <FuelSteps row={row} a={a} />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- who could save the most

export function Savers({ rows, a, limit = 5 }: { rows: ReportRow[]; a: FuelAssumptions; limit?: number }) {
  const top = rows.filter((r) => r.fuel.savableCost > 0).slice(0, limit);
  const max = Math.max(1, ...top.map((r) => r.fuel.savableCost));
  if (top.length === 0) return <p className="muted">No idling or fast driving recorded in this period.</p>;
  return (
    <div className={s.savers}>
      <div className={s.legend}>
        <span>
          <i style={{ background: "var(--accent)" }} />
          Idling
        </span>
        <span>
          <i style={{ background: "#e8a33d" }} />
          Above {a.economicalSpeedKmph} km/h
        </span>
      </div>
      {top.map((r) => {
        const idleShare = (r.fuel.idleLitres * a.pricePerLitre) / max;
        const fastShare = (r.fuel.fastLitres * a.pricePerLitre) / max;
        const advice =
          r.fuel.idleLitres >= r.fuel.fastLitres
            ? `Switch the engine off when standing: ${duration(r.idleSeconds)} idling`
            : `Keep under ${a.economicalSpeedKmph} km/h: ${kms(r.fastKm)} above it`;
        return (
          <div key={r.imei} className={s.saver}>
            <span className={s.vehicle}>
              <span className={s.vehicleName}>{vehicleLabel(r)}</span>
              <span className={s.vehicleSub}>{advice}</span>
            </span>
            <span className={s.saverBar} title={`${rupees(r.fuel.idleLitres * a.pricePerLitre)} idling, ${rupees(r.fuel.fastLitres * a.pricePerLitre)} fast driving`}>
              <span className={s.saverIdle} style={{ width: `${idleShare * 100}%` }} />
              <span className={s.saverFast} style={{ left: `${idleShare * 100}%`, width: `${fastShare * 100}%` }} />
            </span>
            <span className={s.save}>{rupees(r.fuel.savableCost)}</span>
          </div>
        );
      })}
    </div>
  );
}

export { s as reportStyles };
