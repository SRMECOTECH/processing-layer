"use client";

import { Sigma } from "@/components/icons";
import { Callout, Card } from "@/components/ops/kit";
import { ModuleHeader, SettingsCard } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";

const DISTANCE = moduleById("distance")!;

export default function FilterTuningPage() {
  return (
    <div className="stack">
      <ModuleHeader
        module={DISTANCE}
        title="Filter tuning"
        description="The numbers that decide how much the Kalman filter trusts each GPS fix, and the rules that guard the raw distance. Change them in application.yml and restart."
      />

      <div className="grid-2">
        <SettingsCard
          title="Kalman filter"
          subtitle="VehicleStateProcessor · constant-velocity model"
          source="application.yml · gps.stream.vehicle-state"
          settings={[
            { label: "GPS noise σ", value: 12, unit: "m", hint: "Higher trusts fixes less" },
            { label: "Acceleration noise σ", value: 1.5, unit: "m/s²", hint: "Higher follows turns faster" },
            { label: "Outlier gate", value: "99.9 %", hint: "χ² 13.8 with 2 degrees of freedom" },
            { label: "Reset after", value: 3, unit: "outliers", hint: "In a row: the vehicle really moved" },
          ]}
        />
        <SettingsCard
          title="Raw distance guards"
          subtitle="CalculateMovementStep · Traccar's rules"
          source="application.yml · gps.distance"
          settings={[
            { label: "Parking jitter under", value: 10, unit: "m", hint: "Clamped to 0 while standing" },
            { label: "Jitter only below", value: 5, unit: "km/h" },
            { label: "Impossible speed over", value: 200, unit: "km/h", hint: "The step is ignored" },
            { label: "… and a jump over", value: 500, unit: "m" },
          ]}
        />
      </div>

      <Card icon={Sigma} title="The model" subtitle="State in local metres east (x) and north (y).">
        <div className="grid-2">
          <pre className="code">{`state  x = [x, y, vx, vy]
predict x' = F x,  P' = F P Fᵀ + Q(σa, Δt)
update  K = P' Hᵀ (H P' Hᵀ + R(σgps))⁻¹
        x = x' + K (z − H x')

step distance = |position − previous|
added to the trip only while moving`}</pre>
          <Callout title="Tuning tips">
            Raise the GPS noise if parked vehicles still collect distance; raise the acceleration noise if the smoothed track cuts corners on
            tight turns. Compare both distances on the Kalman vs raw page after a change.
          </Callout>
        </div>
      </Card>
    </div>
  );
}
