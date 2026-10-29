"use client";

import { useState } from "react";
import { CalendarDays, Car, Clock, ParkingSquare, Route, Sigma } from "@/components/icons";
import { Callout, Card, Empty, PageHeader, Segmented, Stats, Tile } from "@/components/ops/kit";
import {
  ColumnGuide,
  CsvButton,
  Explain,
  ExplainNote,
  Formula,
  FuelSteps,
  PrintButton,
  PrintHead,
  ReportFiltersBar,
  Savers,
  VehicleTable,
  dayLabel,
  duration,
  hours,
  kilos,
  kms,
  litres,
  monthLabel,
  queryString,
  reportStyles as s,
  rupees,
  useReportQuery,
} from "@/components/ops/reports";
import { formatAgo } from "@/lib/format";
import type { FleetReport, ReportDayRow, ReportFilters } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Metric = "distance" | "cost" | "savable";

/** End of the month: every vehicle's totals from its daily summaries, and the fleet day by day. */
export default function MonthReportPage() {
  const [query, change, ready] = useReportQuery();
  const filters = usePoll<ReportFilters>("/api/reports/filters", 60_000);
  const path = ready ? `/api/reports/month?${queryString({ month: query.month, tenant: query.tenant, imei: query.imei })}` : null;
  const { data: r, error } = usePoll<FleetReport>(path, 60_000);
  const [metric, setMetric] = useState<Metric>("distance");

  return (
    <div className="stack">
      {r && <PrintHead title="Month report" subject={`${monthLabel(r.from.slice(0, 7))} · ${r.tenant ?? "all companies"}`} />}
      <PageHeader
        description="The month for every vehicle, added up from its daily summaries: distance, driving and standing time, stops, idling, speed, fuel and what could have been saved. Each number opens to its calculation."
        meta={[
          ["Month", r ? monthLabel(r.from.slice(0, 7)) : "…"],
          ["Days with readings", r ? String(r.days.length) : "…"],
          ["Updated", r?.summarizedAt ? formatAgo(r.summarizedAt) : "–"],
        ]}
      />

      <Card title="Choose the month" subtitle="Company and vehicle narrow the report">
        <ReportFiltersBar
          mode="month"
          query={query}
          onChange={change}
          filters={filters.data}
          actions={
            <>
              <PrintButton />
              {r && <CsvButton name={`month-report-${r.from.slice(0, 7)}.csv`} rows={() => csvRows(r)} />}
            </>
          }
        />
      </Card>

      {!r ? (
        <Empty>{error ?? "Adding up the month's daily summaries."}</Empty>
      ) : r.vehicles.length === 0 ? (
        <Callout title="No readings that month">Nothing was reported in {monthLabel(r.from.slice(0, 7))} for this choice.</Callout>
      ) : (
        <>
          <MonthTiles r={r} />
          <Card
            icon={CalendarDays}
            title="The fleet day by day"
            subtitle="Hover a bar for the day's numbers"
            actions={
              <Segmented
                label="Show"
                value={metric}
                onChange={setMetric}
                options={[
                  ["distance", "Distance"],
                  ["cost", "Fuel cost"],
                  ["savable", "Could save"],
                ]}
              />
            }
          >
            <DayBars days={r.days} from={r.from} to={r.to} metric={metric} />
          </Card>
          <Card icon={Sigma} title="Where the money goes this month" subtitle="Vehicles that could have saved the most, and how">
            <Savers rows={r.vehicles} a={r.assumptions} limit={8} />
          </Card>
          <Card title="Every vehicle" subtitle="Month totals; click a row for its breakdown" flush>
            <div style={{ padding: "0 var(--card-pad) 6px" }}>
              <ColumnGuide a={r.assumptions} />
            </div>
            <VehicleTable rows={r.vehicles} totals={r.totals} a={r.assumptions} zone={r.zone} showDays />
          </Card>
        </>
      )}
    </div>
  );
}

function MonthTiles({ r }: { r: FleetReport }) {
  const t = r.totals;
  const a = r.assumptions;
  const activeDays = Math.max(1, r.days.length);
  return (
    <Stats columns={3}>
      <Tile
        label="Vehicles"
        icon={Car}
        value={String(t.vehicles)}
        note={`on ${r.days.length} days · ${t.trips} trips`}
        foot={
          <Explain>
            <Formula label="Vehicles" how="vehicles with at least one reading in the month" result={t.vehicles} />
            <Formula label="Days" how="days on which at least one vehicle reported" result={r.days.length} />
          </Explain>
        }
      />
      <Tile
        label="Distance"
        icon={Route}
        value={kms(t.distanceKm)}
        note={`${kms(t.distanceKm / activeDays)} a day`}
        foot={
          <Explain>
            <Formula label="Distance" how="sum of every vehicle's daily distance" result={kms(t.distanceKm)} />
            <Formula label="A day" how={`${kms(t.distanceKm)} ÷ ${activeDays} days with readings`} result={kms(t.distanceKm / activeDays)} />
          </Explain>
        }
      />
      <Tile
        label="Driving / standing"
        icon={Clock}
        value={duration(t.movingSeconds)}
        note={`standing ${duration(t.stoppedSeconds)}`}
        foot={
          <Explain>
            <Formula label="Driving" how="sum of the daily driving times" result={hours(t.movingSeconds)} />
            <Formula label="Utilisation" how={`${hours(t.movingSeconds)} ÷ (${hours(t.movingSeconds)} + ${hours(t.stoppedSeconds)})`} result={`${t.utilisationPercent.toFixed(1)} %`} />
          </Explain>
        }
      />
      <Tile
        label="Engine idling"
        icon={ParkingSquare}
        value={duration(t.idleSeconds)}
        note={`${litres(t.fuel.idleLitres)} burnt standing`}
        foot={
          <Explain>
            <Formula label="Idling" how="sum of the daily IDLING time (engine on, standing past 5 min)" result={hours(t.idleSeconds)} />
            <Formula label="Fuel" how={`${hours(t.idleSeconds)} × ${a.idleLitresPerHour} L/h`} result={litres(t.fuel.idleLitres)} />
          </Explain>
        }
      />
      <Tile
        label="Fuel used (estimate)"
        icon={Route}
        value={rupees(t.fuel.cost)}
        note={`${litres(t.fuel.usedLitres)} · ${kilos(t.fuel.co2Kg)} CO₂`}
        foot={
          <Explain>
            <FuelSteps row={t} a={a} />
          </Explain>
        }
      />
      <Tile
        label="Could have been saved"
        icon={Sigma}
        value={rupees(t.fuel.savableCost)}
        tone="good"
        note={`${litres(t.fuel.savableLitres)} · a year like this ${rupees(t.fuel.savableCost * 12)}`}
        foot={
          <Explain>
            <Formula label="Idling" how={litres(t.fuel.idleLitres)} result={rupees(t.fuel.idleLitres * a.pricePerLitre)} />
            <Formula label="Fast driving" how={`${kms(t.fastKm)} above ${a.economicalSpeedKmph} km/h`} result={rupees(t.fuel.fastLitres * a.pricePerLitre)} />
            <Formula label="Month" how={`${litres(t.fuel.savableLitres)} × ₹${a.pricePerLitre}/L`} result={rupees(t.fuel.savableCost)} />
            <Formula label="Year" how={`${rupees(t.fuel.savableCost)} × 12`} result={rupees(t.fuel.savableCost * 12)} />
            <ExplainNote>A month in progress shows the days so far.</ExplainNote>
          </Explain>
        }
      />
    </Stats>
  );
}

/** One bar per day of the month; days without readings stay empty. */
function DayBars({ days, from, to, metric }: { days: ReportDayRow[]; from: string; to: string; metric: Metric }) {
  const value = (d: ReportDayRow) => (metric === "distance" ? d.distanceKm : metric === "cost" ? d.fuel.cost : d.fuel.savableCost);
  const text = (d: ReportDayRow) => (metric === "distance" ? kms(d.distanceKm) : rupees(value(d)));
  const byDay = new Map(days.map((d) => [d.day, d]));
  const all: string[] = [];
  for (let d = new Date(`${from}T12:00:00Z`); d.toISOString().slice(0, 10) <= to; d.setUTCDate(d.getUTCDate() + 1)) {
    all.push(d.toISOString().slice(0, 10));
  }
  const max = Math.max(1, ...days.map(value));
  return (
    <>
      <div className={s.days}>
        {all.map((day) => {
          const row = byDay.get(day);
          return (
            <div
              key={day}
              className={s.dayBar}
              title={row ? `${dayLabel(day)}: ${text(row)} · ${row.vehicles} vehicles · ${row.trips} trips · idling ${duration(row.idleSeconds)}` : `${dayLabel(day)}: no readings`}
            >
              <div className={s.dayFill} style={{ height: row ? `${(value(row) / max) * 100}%` : 0 }} />
            </div>
          );
        })}
      </div>
      <div className={s.dayLabels}>
        {all.map((day) => (
          <span key={day}>{Number(day.slice(8))}</span>
        ))}
      </div>
    </>
  );
}

function csvRows(r: FleetReport): (string | number)[][] {
  const header = [
    "Month", "Vehicle", "IMEI", "Company", "Days", "Distance km", "Driving h", "Standing h", "Stops", "Idling h", "Trips", "Max speed km/h",
    `Km above ${r.assumptions.economicalSpeedKmph} km/h`, "Over-speed episodes", "Harsh events", "Fuel used L", "Cost INR", "Could save L",
    "Could save INR", "CO2 kg",
  ];
  const rows = r.vehicles.map((v) => [
    r.from.slice(0, 7), v.registration ?? "", v.imei ?? "", v.tenant ?? "", v.days, v.distanceKm, v.movingSeconds / 3600, v.stoppedSeconds / 3600,
    v.stops, v.idleSeconds / 3600, v.trips, v.maxSpeedKmph, v.fastKm, v.overspeedEvents, v.harshEvents, v.fuel.usedLitres, v.fuel.cost,
    v.fuel.savableLitres, v.fuel.savableCost, v.fuel.co2Kg,
  ]);
  return [header, ...rows];
}
