"use client";

import { useEffect, useState } from "react";
import { RotateCcw, Save, Settings2 } from "@/components/icons";
import { Callout, Card, Empty, PageHeader } from "@/components/ops/kit";
import { Explain, ExplainNote, Formula, kilos, kms, litres, rupees } from "@/components/ops/reports";
import type { FuelAssumptions } from "@/lib/types";
import { sendJson, usePoll } from "@/lib/use-poll";

const DEFAULTS: FuelAssumptions = {
  fuelType: "diesel",
  pricePerLitre: 92,
  mileageKmPerLitre: 4,
  idleLitresPerHour: 2.5,
  economicalSpeedKmph: 60,
  fastDrivingPenaltyPercent: 20,
  co2KgPerLitre: 2.68,
};

const FIELDS: { key: Exclude<keyof FuelAssumptions, "fuelType">; label: string; unit: string; step: number; hint: string }[] = [
  { key: "pricePerLitre", label: "Fuel price", unit: "₹/L", step: 0.5, hint: "What the fleet pays at the pump" },
  { key: "mileageKmPerLitre", label: "Mileage while driving", unit: "km/L", step: 0.1, hint: "Loaded truck about 3.5–4.5, car 12–18" },
  { key: "idleLitresPerHour", label: "Idle burn", unit: "L/h", step: 0.1, hint: "Engine on, standing: truck 2–3, car 0.6–1" },
  { key: "economicalSpeedKmph", label: "Economical speed", unit: "km/h", step: 5, hint: "Above it every km costs more fuel" },
  { key: "fastDrivingPenaltyPercent", label: "Extra fuel above it", unit: "%", step: 1, hint: "Extra fuel per km driven faster" },
  { key: "co2KgPerLitre", label: "CO₂ per litre", unit: "kg/L", step: 0.01, hint: "Diesel 2.68, petrol 2.31" },
];

/** The numbers behind every fuel and money figure in the reports. Saved in PostgreSQL; every report uses them at once. */
export default function AssumptionsPage() {
  const { data, error, refresh } = usePoll<FuelAssumptions>("/api/reports/assumptions", 60_000);
  const [draft, setDraft] = useState<FuelAssumptions>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "info" | "warn"; text: string }>();

  useEffect(() => {
    if (data && !draft) setDraft(data);
  }, [data, draft]);

  async function save(values: FuelAssumptions) {
    setBusy(true);
    setMessage(undefined);
    try {
      const saved = await sendJson<FuelAssumptions>("/api/reports/assumptions", "PUT", values);
      setDraft(saved);
      setMessage({
        tone: "info",
        text:
          data && saved.economicalSpeedKmph !== data.economicalSpeedKmph
            ? "Saved. Every report uses the new values now; the km above the new economical speed are being counted again in the background."
            : "Saved. Every report uses the new values now.",
      });
      refresh();
    } catch (problem) {
      setMessage({ tone: "warn", text: problem instanceof Error ? problem.message : "Could not save" });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <PageHeader description="The fleet's own figures behind every litre, rupee and kilo of CO₂ in the reports. No fuel sensor is connected yet, so fuel is estimated from distance, idling and speed with these numbers." />

      {!draft ? (
        <Empty>{error ?? "Loading the assumptions."}</Empty>
      ) : (
        <div className="grid-main-side">
          <Card icon={Settings2} title="Cost assumptions" subtitle="report_setting in PostgreSQL · kept by Clear database">
            <label className="field" style={{ maxWidth: 260, marginBottom: 18 }}>
              <span className="field-label">Fuel</span>
              <select
                className="select"
                value={draft.fuelType}
                onChange={(e) => {
                  const fuelType = e.target.value as FuelAssumptions["fuelType"];
                  setDraft({ ...draft, fuelType, co2KgPerLitre: fuelType === "petrol" ? 2.31 : 2.68 });
                }}
              >
                <option value="diesel">Diesel</option>
                <option value="petrol">Petrol</option>
              </select>
            </label>
            <div className="form-grid">
              {FIELDS.map((f) => (
                <label className="field" key={f.key}>
                  <span className="field-label">{f.label}</span>
                  <span className="input-unit">
                    <input
                      className="input-box mono"
                      type="number"
                      min={0}
                      step={f.step}
                      value={draft[f.key]}
                      onChange={(e) => setDraft({ ...draft, [f.key]: Number(e.target.value) })}
                    />
                    <span>{f.unit}</span>
                  </span>
                  <span className="field-hint">{f.hint}</span>
                </label>
              ))}
            </div>
            <div className="card-actions">
              <button type="button" className="btn primary" disabled={busy} onClick={() => save(draft)}>
                <Save size={15} />
                Save
              </button>
              <button type="button" className="btn soft" disabled={busy} onClick={() => setDraft({ ...DEFAULTS })}>
                <RotateCcw size={15} />
                Defaults (loaded diesel truck)
              </button>
            </div>
            {message && (
              <div style={{ marginTop: 14 }}>
                <Callout tone={message.tone}>{message.text}</Callout>
              </div>
            )}
          </Card>
          <Example a={draft} />
        </div>
      )}
    </div>
  );
}

/** The formulas with these numbers, on an example day: 200 km, 1.5 h idling, 40 km fast. */
function Example({ a }: { a: FuelAssumptions }) {
  const distance = 200;
  const idleHours = 1.5;
  const fast = 40;
  const driving = distance / a.mileageKmPerLitre;
  const idle = idleHours * a.idleLitresPerHour;
  const extra = (fast / a.mileageKmPerLitre) * (a.fastDrivingPenaltyPercent / 100);
  const used = driving + idle + extra;
  const savable = idle + extra;
  return (
    <Card title="An example day" subtitle={`${kms(distance)}, ${idleHours} h idling, ${kms(fast)} above ${a.economicalSpeedKmph} km/h`}>
      <Formula label="Driving fuel" how={`${distance} km ÷ ${a.mileageKmPerLitre} km/L`} result={litres(driving)} />
      <Formula label="Idling fuel" how={`${idleHours} h × ${a.idleLitresPerHour} L/h`} result={litres(idle)} />
      <Formula label="Fast-driving extra" how={`${fast} km ÷ ${a.mileageKmPerLitre} km/L × ${a.fastDrivingPenaltyPercent} %`} result={litres(extra)} />
      <Formula label="Fuel used" how="driving + idling + extra" result={litres(used)} />
      <Formula label="Cost" how={`${litres(used)} × ₹${a.pricePerLitre}/L`} result={rupees(used * a.pricePerLitre)} />
      <Formula label="Could be saved" how={`(${litres(idle)} + ${litres(extra)}) × ₹${a.pricePerLitre}/L`} result={rupees(savable * a.pricePerLitre)} />
      <Formula label="CO₂" how={`${litres(used)} × ${a.co2KgPerLitre} kg/L`} result={kilos(used * a.co2KgPerLitre)} />
      <Explain title="Why these formulas">
        <ExplainNote>
          Driving fuel follows distance and mileage. An idling engine burns fuel by the hour while the vehicle goes nowhere: all of it is savable by
          switching off. Above the economical speed air drag and engine load rise, so each km costs more; the penalty is that extra share.
          Fuel sensors or CAN-bus fuel readings, once connected, can replace these estimates.
        </ExplainNote>
      </Explain>
    </Card>
  );
}
