"use client";

import { useState } from "react";
import { Layers3 } from "@/components/icons";
import { Card, DemoButton, Switch, Tag } from "@/components/ops/kit";
import { ModuleHeader, SettingsCard } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";

const OVERSPEED = moduleById("overspeed")!;

const BANDS: { band: string; when: string; severity: "MEDIUM" | "HIGH" | "CRITICAL"; action: string }[] = [
  { band: "Warning", when: "Over the limit, under limit + 10 km/h", severity: "MEDIUM", action: "Log the event" },
  { band: "Breach", when: "Limit + 10 to + 25 km/h, GPS verified", severity: "HIGH", action: "Notify the operations desk" },
  { band: "Severe", when: "Over limit + 25 km/h, GPS verified", severity: "CRITICAL", action: "Call the driver, escalate after 5 min" },
];

export default function AlertPolicyPage() {
  const [suppressSuspect, setSuppressSuspect] = useState(true);
  const [nightStricter, setNightStricter] = useState(false);

  return (
    <div className="stack">
      <ModuleHeader
        module={OVERSPEED}
        title="Alert policy"
        description="How a verified over-speed becomes an alert: severity bands above the entity's limit, what to do for each, and when to stay quiet. The speed rules decide whether there is an episode; this policy decides who hears about it."
        status="planned"
      />

      <Card icon={Layers3} title="Severity bands" subtitle="Relative to the vehicle's own limit." live={false} flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Band</th>
                <th>When</th>
                <th>Severity</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {BANDS.map((b) => (
                <tr key={b.band}>
                  <td>
                    <strong>{b.band}</strong>
                  </td>
                  <td className="muted">{b.when}</td>
                  <td>
                    <Tag tone={b.severity === "CRITICAL" ? "bad" : b.severity === "HIGH" ? "warn" : "neutral"}>{b.severity}</Tag>
                  </td>
                  <td>{b.action}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Conditions" subtitle="Example switches; not saved yet." live={false}>
        <div className="switch-list">
          <label className="switch-row">
            <span>
              <span className="switch-title">Do not alert on SENSOR_SUSPECT</span>
              <span className="switch-sub">Send a device-health ticket instead of a driver alert</span>
            </span>
            <Switch checked={suppressSuspect} onChange={setSuppressSuspect} label="Suppress sensor-suspect alerts" />
          </label>
          <label className="switch-row">
            <span>
              <span className="switch-title">Stricter at night (22:00–05:00)</span>
              <span className="switch-sub">Every band starts 10 km/h lower</span>
            </span>
            <Switch checked={nightStricter} onChange={setNightStricter} label="Stricter at night" />
          </label>
        </div>
        <div className="card-actions">
          <DemoButton primary>Save policy</DemoButton>
        </div>
      </Card>

      <SettingsCard
        title="Repeat and cooldown"
        source="planned"
        settings={[
          { label: "Cooldown per vehicle", value: 10, unit: "min", hint: "No second alert for the same episode" },
          { label: "Re-alert if still speeding after", value: 5, unit: "min" },
          { label: "Group alerts of one trip", value: "yes" },
          { label: "Daily summary per company", value: "07:00" },
        ]}
      />
    </div>
  );
}
