"use client";

import { useState } from "react";
import { Gauge, ParkingSquare, Sigma, Waves } from "@/components/icons";
import { Callout, Card, Empty, PageHeader, Stats, Tile } from "@/components/ops/kit";
import {
  Explain,
  ExplainNote,
  Formula,
  PrintButton,
  PrintHead,
  ReportFiltersBar,
  Savers,
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
  vehicleLabel,
} from "@/components/ops/reports";
import type { FleetReport, ReportFilters } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

/**
 * The business case: what idling and fast driving cost this month, who causes it, and what a realistic target
 * would save in a month and a year. Every figure opens to its calculation.
 */
export default function SavingsPage() {
  const [query, change, ready] = useReportQuery();
  const filters = usePoll<ReportFilters>("/api/reports/filters", 60_000);
  const path = ready ? `/api/reports/month?${queryString({ month: query.month, tenant: query.tenant, imei: query.imei })}` : null;
  const { data: r, error } = usePoll<FleetReport>(path, 60_000);
  const [idleCut, setIdleCut] = useState(50);
  const [fastCut, setFastCut] = useState(50);

  return (
    <div className="stack">
      {r && <PrintHead title="Fuel & savings" subject={`${monthLabel(r.from.slice(0, 7))} · ${r.tenant ?? "all companies"}`} />}
      <PageHeader
        description="Fuel burnt for nothing: engines idling while the vehicle stands, and kilometres driven faster than the economical speed. Who wastes the most, and what a realistic target is worth in rupees and CO₂."
        meta={[
          ["Month", r ? monthLabel(r.from.slice(0, 7)) : "…"],
          ["Fuel", r ? `${r.assumptions.fuelType} at ₹${r.assumptions.pricePerLitre}/L` : "…"],
        ]}
      />

      <Card title="Choose the month">
        <ReportFiltersBar mode="month" query={query} onChange={change} filters={filters.data} actions={<PrintButton />} />
      </Card>

      {!r ? (
        <Empty>{error ?? "Adding up the month."}</Empty>
      ) : r.vehicles.length === 0 ? (
        <Callout title="No readings that month">Nothing to save: no vehicle reported.</Callout>
      ) : (
        <SavingsBody r={r} idleCut={idleCut} fastCut={fastCut} setIdleCut={setIdleCut} setFastCut={setFastCut} />
      )}
    </div>
  );
}

type BodyProps = { r: FleetReport; idleCut: number; fastCut: number; setIdleCut: (v: number) => void; setFastCut: (v: number) => void };

function SavingsBody({ r, idleCut, fastCut, setIdleCut, setFastCut }: BodyProps) {
  const t = r.totals;
  const a = r.assumptions;
  const idleMoney = t.fuel.idleLitres * a.pricePerLitre;
  const fastMoney = t.fuel.fastLitres * a.pricePerLitre;
  const targetLitres = (t.fuel.idleLitres * idleCut) / 100 + (t.fuel.fastLitres * fastCut) / 100;
  const targetMoney = targetLitres * a.pricePerLitre;
  const share = t.fuel.cost > 0 ? (100 * t.fuel.savableCost) / t.fuel.cost : 0;
  const worst = r.vehicles.slice(0, Math.max(1, Math.ceil(r.vehicles.length * 0.2)));
  const worstMoney = worst.reduce((sum, v) => sum + v.fuel.savableCost, 0);

  return (
    <>
      <Stats columns={4}>
        <Tile
          label="Wasted this month"
          icon={Sigma}
          value={rupees(t.fuel.savableCost)}
          tone="warn"
          note={`${share.toFixed(1)} % of the fuel bill`}
          foot={
            <Explain>
              <Formula label="Idling" how={`${hours(t.idleSeconds)} × ${a.idleLitresPerHour} L/h × ₹${a.pricePerLitre}`} result={rupees(idleMoney)} />
              <Formula
                label="Fast driving"
                how={`${kms(t.fastKm)} ÷ ${a.mileageKmPerLitre} km/L × ${a.fastDrivingPenaltyPercent} % × ₹${a.pricePerLitre}`}
                result={rupees(fastMoney)}
              />
              <Formula label="Share of the bill" how={`${rupees(t.fuel.savableCost)} ÷ ${rupees(t.fuel.cost)}`} result={`${share.toFixed(1)} %`} />
            </Explain>
          }
        />
        <Tile
          label="Idling"
          icon={ParkingSquare}
          value={rupees(idleMoney)}
          note={`${duration(t.idleSeconds)} · ${litres(t.fuel.idleLitres)}`}
          foot={
            <Explain>
              <Formula label="Hours" how="engine on while standing past 5 min, all vehicles" result={hours(t.idleSeconds)} />
              <Formula label="Litres" how={`${hours(t.idleSeconds)} × ${a.idleLitresPerHour} L/h`} result={litres(t.fuel.idleLitres)} />
            </Explain>
          }
        />
        <Tile
          label={`Above ${a.economicalSpeedKmph} km/h`}
          icon={Gauge}
          value={rupees(fastMoney)}
          note={`${kms(t.fastKm)} · ${litres(t.fuel.fastLitres)} extra`}
          foot={
            <Explain>
              <Formula label="Kilometres" how={`distance of readings faster than ${a.economicalSpeedKmph} km/h`} result={kms(t.fastKm)} />
              <Formula label="Extra litres" how={`${kms(t.fastKm)} ÷ ${a.mileageKmPerLitre} km/L × ${a.fastDrivingPenaltyPercent} %`} result={litres(t.fuel.fastLitres)} />
            </Explain>
          }
        />
        <Tile
          label="CO₂ that need not be"
          icon={Waves}
          value={kilos(t.fuel.savableCo2Kg)}
          note={`of ${kilos(t.fuel.co2Kg)} in all`}
          foot={
            <Explain>
              <Formula label="CO₂" how={`${litres(t.fuel.savableLitres)} × ${a.co2KgPerLitre} kg/L`} result={kilos(t.fuel.savableCo2Kg)} />
              <ExplainNote>{a.co2KgPerLitre} kg of CO₂ per litre of {a.fuelType} burnt.</ExplainNote>
            </Explain>
          }
        />
      </Stats>

      <Card icon={Sigma} title="Set a target" subtitle="Move the sliders: what a realistic change in habits is worth">
        <div className={s.whatIf}>
          <label className={s.slider}>
            <span className="field-label">Cut idling by {idleCut} %</span>
            <input type="range" min={0} max={100} step={5} value={idleCut} onChange={(e) => setIdleCut(Number(e.target.value))} />
            <span className="field-hint">Switch the engine off at stops longer than a few minutes</span>
          </label>
          <label className={s.slider}>
            <span className="field-label">
              Cut driving above {a.economicalSpeedKmph} km/h by {fastCut} %
            </span>
            <input type="range" min={0} max={100} step={5} value={fastCut} onChange={(e) => setFastCut(Number(e.target.value))} />
            <span className="field-hint">Speed governors, driver coaching, the over-speed alerts</span>
          </label>
          <div>
            <div className="field-label">Saved a month</div>
            <div className={`${s.big} ${s.save}`}>{rupees(targetMoney)}</div>
            <div className="field-hint">
              {litres(targetLitres)} · {kilos(targetLitres * a.co2KgPerLitre)} CO₂ · a year: {rupees(targetMoney * 12)}
            </div>
          </div>
        </div>
        <Explain>
          <Formula label="Idling saved" how={`${litres(t.fuel.idleLitres)} × ${idleCut} %`} result={litres((t.fuel.idleLitres * idleCut) / 100)} />
          <Formula label="Fast driving saved" how={`${litres(t.fuel.fastLitres)} × ${fastCut} %`} result={litres((t.fuel.fastLitres * fastCut) / 100)} />
          <Formula label="A month" how={`${litres(targetLitres)} × ₹${a.pricePerLitre}/L`} result={rupees(targetMoney)} />
          <Formula label="A year" how={`${rupees(targetMoney)} × 12`} result={rupees(targetMoney * 12)} />
          <ExplainNote>A month in progress counts the days so far: the full month will be larger.</ExplainNote>
        </Explain>
      </Card>

      <div className="grid-main-side">
        <Card title="Who wastes the most" subtitle="Most savable rupees first, with the habit to change">
          <Savers rows={r.vehicles} a={a} limit={10} />
        </Card>
        <Card title="Where to start" subtitle="The few vehicles that matter most">
          <dl className="kv">
            <div className="kv-row">
              <dt>Worst 20 % of vehicles</dt>
              <dd>
                {worst.length} of {r.vehicles.length}
              </dd>
            </div>
            <div className="kv-row">
              <dt>Their waste</dt>
              <dd>{rupees(worstMoney)}</dd>
            </div>
            <div className="kv-row">
              <dt>Share of all waste</dt>
              <dd>{t.fuel.savableCost > 0 ? ((100 * worstMoney) / t.fuel.savableCost).toFixed(0) : 0} %</dd>
            </div>
            <div className="kv-row">
              <dt>Most idling</dt>
              <dd>{mostIdling(r)}</dd>
            </div>
          </dl>
          <Explain>
            <Formula label="Worst 20 %" how="vehicles ranked by savable rupees, the top fifth (at least one)" result={`${worst.length} vehicles`} />
            <Formula label="Their share" how={`${rupees(worstMoney)} ÷ ${rupees(t.fuel.savableCost)}`} result={`${t.fuel.savableCost > 0 ? ((100 * worstMoney) / t.fuel.savableCost).toFixed(0) : 0} %`} />
          </Explain>
        </Card>
      </div>
    </>
  );
}

function mostIdling(r: FleetReport): string {
  const v = [...r.vehicles].sort((a, b) => b.idleSeconds - a.idleSeconds)[0];
  return v && v.idleSeconds > 0 ? `${vehicleLabel(v)} · ${duration(v.idleSeconds)}` : "–";
}
