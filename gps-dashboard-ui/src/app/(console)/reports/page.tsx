"use client";

import { useState } from "react";
import { Car, Clock, Gauge, ParkingSquare, Route, Sigma } from "@/components/icons";
import { Callout, Card, Empty, PageHeader, Stats, Tile } from "@/components/ops/kit";
import {
  ColumnGuide,
  CsvButton,
  Explain,
  ExplainNote,
  Formula,
  FuelSteps,
  PrintButton,
  PrintHead,
  RefreshButton,
  ReportFiltersBar,
  Savers,
  VehicleTable,
  dayLabel,
  duration,
  hours,
  kilos,
  kms,
  litres,
  queryString,
  rupees,
  useReportQuery,
  vehicleLabel,
} from "@/components/ops/reports";
import { formatAgo } from "@/lib/format";
import type { FleetReport, ReportFilters } from "@/lib/types";
import { post, usePoll } from "@/lib/use-poll";

/** End of the day: every vehicle's distance, stops, idling, speed, fuel and what could have been saved. */
export default function DayReportPage() {
  const [query, change, ready] = useReportQuery();
  const filters = usePoll<ReportFilters>("/api/reports/filters", 60_000);
  const path = ready ? `/api/reports/day?${queryString({ date: query.date, tenant: query.tenant, imei: query.imei })}` : null;
  const { data: r, error, refresh } = usePoll<FleetReport>(path, 30_000);
  const [busy, setBusy] = useState(false);

  async function rebuild() {
    setBusy(true);
    try {
      await post(`/api/reports/refresh?date=${query.date}`);
      refresh();
    } finally {
      setBusy(false);
    }
  }

  const subject = r ? `${dayLabel(r.from)} · ${r.tenant ?? "all companies"} · ${r.imei ? vehicleLabel(r.vehicles[0] ?? { registration: null, imei: r.imei }) : "all vehicles"}` : "";

  return (
    <div className="stack">
      {r && <PrintHead title="Day report" subject={subject} />}
      <PageHeader
        description="Every vehicle's day, midnight to midnight IST: how far it drove, where it stood, how long the engine idled, and what the fuel cost and could have saved. Open any number's drop-down to see how it is worked out."
        meta={[
          ["Day", r ? dayLabel(r.from) : "…"],
          ["Updated", r?.summarizedAt ? formatAgo(r.summarizedAt) : "–"],
          ["Fuel", r ? `${r.assumptions.fuelType} at ₹${r.assumptions.pricePerLitre}/L` : "…"],
        ]}
      />

      <Card title="Choose the day" subtitle="Company and vehicle narrow the report; the address keeps your choice for bookmarking and printing">
        <ReportFiltersBar
          mode="day"
          query={query}
          onChange={change}
          filters={filters.data}
          actions={
            <>
              <RefreshButton onClick={rebuild} busy={busy} />
              <PrintButton />
              {r && <CsvButton name={`day-report-${r.from}.csv`} rows={() => csvRows(r)} />}
            </>
          }
        />
      </Card>

      {!r ? (
        <Empty>{error ?? "Loading the day's summaries."}</Empty>
      ) : r.vehicles.length === 0 ? (
        <Callout title="No readings that day">
          No vehicle reported on {dayLabel(r.from)}{r.tenant ? ` for ${r.tenant}` : ""}. Summaries are rebuilt every 5 minutes; positions of earlier
          days are summarized once after a start.
        </Callout>
      ) : (
        <>
          <DayTiles r={r} />
          <div className="grid-main-side">
            <Card icon={Sigma} title="Where the money goes" subtitle="Vehicles that could have saved the most fuel today, and how">
              <Savers rows={r.vehicles} a={r.assumptions} />
            </Card>
            <Card icon={Gauge} title="Driving behaviour" subtitle="Events raised by the stream processors today">
              <dl className="kv">
                <div className="kv-row">
                  <dt>Over-speed episodes</dt>
                  <dd>{r.totals.overspeedEvents}</dd>
                </div>
                <div className="kv-row">
                  <dt>Harsh braking / acceleration</dt>
                  <dd>{r.totals.harshEvents}</dd>
                </div>
                <div className="kv-row">
                  <dt>Highest speed</dt>
                  <dd>{r.totals.maxSpeedKmph} km/h</dd>
                </div>
                <div className="kv-row">
                  <dt>Stops with the engine on</dt>
                  <dd>
                    {r.totals.engineOnStops} of {r.totals.stops}
                  </dd>
                </div>
              </dl>
              <Explain>
                <Formula label="Over-speed" how="episodes from the speed rules: over the limit for N pings and X seconds, checked against GPS" result="count" />
                <Formula label="Harsh events" how="vehicle state: speeding up faster than 2.5 m/s² or braking harder than 3 m/s² between readings" result="count" />
                <Formula label="Engine-on stops" how="STOPPAGE episodes (standing, engine on) against all stops detected" result="count" />
              </Explain>
            </Card>
          </div>
          <Card title="Every vehicle" subtitle="Click a row for its full breakdown; the fleet total is the last row" flush>
            <div style={{ padding: "0 var(--card-pad) 6px" }}>
              <ColumnGuide a={r.assumptions} />
            </div>
            <VehicleTable rows={r.vehicles} totals={r.totals} a={r.assumptions} zone={r.zone} day={r.from} />
          </Card>
        </>
      )}
    </div>
  );
}

function DayTiles({ r }: { r: FleetReport }) {
  const t = r.totals;
  const a = r.assumptions;
  return (
    <Stats columns={3}>
      <Tile
        label="Vehicles that reported"
        icon={Car}
        value={String(t.vehicles)}
        note={`${t.trips} trips`}
        foot={
          <Explain>
            <Formula label="Vehicles" how="vehicles with at least one stored position between 00:00 and 24:00 IST" result={t.vehicles} />
            <Formula label="Trips" how="trips that started that day (trip detector: moved 200 m away from a stop)" result={t.trips} />
          </Explain>
        }
      />
      <Tile
        label="Distance"
        icon={Route}
        value={kms(t.distanceKm)}
        note={`${(t.distanceKm / Math.max(1, t.vehicles)).toFixed(0)} km a vehicle`}
        foot={
          <Explain>
            <Formula label="Distance" how="sum over every vehicle of the distance between its consecutive readings" result={kms(t.distanceKm)} />
            <Formula label="Per vehicle" how={`${kms(t.distanceKm)} ÷ ${t.vehicles} vehicles`} result={kms(t.distanceKm / Math.max(1, t.vehicles))} />
            <ExplainNote>GPS jitter under 10 m and jumps over 500 m that would need more than 200 km/h are not counted.</ExplainNote>
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
            <Formula label="Driving" how="time between readings while MOVING, all vehicles" result={hours(t.movingSeconds)} />
            <Formula label="Standing" how="time between readings while STOPPED, all vehicles" result={hours(t.stoppedSeconds)} />
            <Formula label="Utilisation" how={`${hours(t.movingSeconds)} ÷ (${hours(t.movingSeconds)} + ${hours(t.stoppedSeconds)})`} result={`${t.utilisationPercent.toFixed(1)} %`} />
            <ExplainNote>
              A silence over 10 minutes before a moving reading counts as neither; before a standing reading up to 1 hour counts as standing (a parked
              tracker reports rarely).
            </ExplainNote>
          </Explain>
        }
      />
      <Tile
        label="Engine idling"
        icon={ParkingSquare}
        value={duration(t.idleSeconds)}
        note={`${litres(t.fuel.idleLitres)} burnt standing`}
        tone={t.idleSeconds > 3600 * t.vehicles * 0.5 ? "warn" : undefined}
        foot={
          <Explain>
            <Formula
              label="Idling"
              how="IDLING episodes (engine on, standing longer than 5 min), overlaps once, between each vehicle's first and last reading, at most its standing time"
              result={hours(t.idleSeconds)}
            />
            <Formula label="Fuel burnt" how={`${hours(t.idleSeconds)} × ${a.idleLitresPerHour} L/h`} result={litres(t.fuel.idleLitres)} />
            <Formula label="Money" how={`${litres(t.fuel.idleLitres)} × ₹${a.pricePerLitre}/L`} result={rupees(t.fuel.idleLitres * a.pricePerLitre)} />
          </Explain>
        }
      />
      <Tile
        label="Fuel used (estimate)"
        icon={Gauge}
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
        note={`${litres(t.fuel.savableLitres)} · ${kilos(t.fuel.savableCo2Kg)} CO₂`}
        foot={
          <Explain>
            <Formula label="Idling" how={`${litres(t.fuel.idleLitres)} burnt standing with the engine on`} result={rupees(t.fuel.idleLitres * a.pricePerLitre)} />
            <Formula
              label="Fast driving"
              how={`${kms(t.fastKm)} above ${a.economicalSpeedKmph} km/h ÷ ${a.mileageKmPerLitre} km/L × ${a.fastDrivingPenaltyPercent} %`}
              result={rupees(t.fuel.fastLitres * a.pricePerLitre)}
            />
            <Formula label="Total" how={`${litres(t.fuel.savableLitres)} × ₹${a.pricePerLitre}/L`} result={rupees(t.fuel.savableCost)} />
            <Formula label="A month like this" how={`${rupees(t.fuel.savableCost)} × 30 days`} result={rupees(t.fuel.savableCost * 30)} />
          </Explain>
        }
      />
    </Stats>
  );
}

function csvRows(r: FleetReport): (string | number)[][] {
  const header = [
    "Day", "Vehicle", "IMEI", "Company", "Readings", "Distance km", "Driving h", "Standing h", "Stops", "Stop time h", "Idling h", "Trips",
    "Max speed km/h", `Km above ${r.assumptions.economicalSpeedKmph} km/h`, "Over-speed episodes", "Harsh events", "Fuel used L", "Idling fuel L",
    "Fast-driving extra L", "Cost INR", "Could save L", "Could save INR", "CO2 kg",
  ];
  const rows = r.vehicles.map((v) => [
    r.from, v.registration ?? "", v.imei ?? "", v.tenant ?? "", v.readings, v.distanceKm, v.movingSeconds / 3600, v.stoppedSeconds / 3600, v.stops,
    v.stopSeconds / 3600, v.idleSeconds / 3600, v.trips, v.maxSpeedKmph, v.fastKm, v.overspeedEvents, v.harshEvents, v.fuel.usedLitres,
    v.fuel.idleLitres, v.fuel.fastLitres, v.fuel.cost, v.fuel.savableLitres, v.fuel.savableCost, v.fuel.co2Kg,
  ]);
  return [header, ...rows];
}
