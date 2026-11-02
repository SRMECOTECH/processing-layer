"use client";

import { useEffect, useState } from "react";
import { Layers3, MapPinned, Save } from "@/components/icons";
import { Callout, Card, Empty, Switch, Tag } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import { sendJson, usePoll } from "@/lib/use-poll";

const OVERSPEED = moduleById("overspeed")!;

type Policy = {
  breachOverKmph: number;
  severeOverKmph: number;
  suppressSensorSuspect: boolean;
  nightStricterKmph: number;
  nightStartHour: number;
  nightEndHour: number;
};

type Zone = { id: number; code: string; name: string; role: string; speedLimitKmph: number; radiusMeters: number };

/** How a confirmed over-speed is graded, and where the limit is lower (geofences, night): saved in Redis, live in 5 s. */
export default function AlertPolicyPage() {
  const saved = usePoll<Policy>("/api/speed/policy", 30_000);
  const zones = usePoll<Zone[]>("/api/speed/zones", 30_000);
  const [p, setP] = useState<Policy>();
  const [note, setNote] = useState<{ tone: "info" | "warn"; text: string }>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (saved.data && !p) setP(saved.data);
  }, [saved.data, p]);

  async function save() {
    if (!p) return;
    setBusy(true);
    try {
      setP(await sendJson<Policy>("/api/speed/policy", "PUT", p));
      setNote({ tone: "info", text: "Saved. Every speed consumer uses it within 5 seconds." });
    } catch (problem) {
      setNote({ tone: "warn", text: problem instanceof Error ? problem.message : "Not saved" });
    } finally {
      setBusy(false);
    }
  }

  const set = (change: Partial<Policy>) => p && setP({ ...p, ...change });
  const bands = p
    ? [
        { band: "Warning", when: `Over the limit, under limit + ${p.breachOverKmph} km/h`, severity: "MEDIUM", tone: "neutral" as const },
        { band: "Breach", when: `Limit + ${p.breachOverKmph} to + ${p.severeOverKmph} km/h`, severity: "HIGH", tone: "warn" as const },
        { band: "Severe", when: `Over limit + ${p.severeOverKmph} km/h`, severity: "CRITICAL", tone: "bad" as const },
        { band: "Suspect", when: "The GPS contradicted the speedometer", severity: p.suppressSensorSuspect ? "LOW (no driver alert)" : "graded by speed", tone: "neutral" as const },
      ]
    : [];

  return (
    <div className="stack">
      <ModuleHeader
        module={OVERSPEED}
        title="Alert policy"
        description="The speed rules decide whether there is an episode; this policy lowers the limit where it should be lower (inside a geofence with its own limit, at night) and grades each episode by how far over it went."
        status="live"
      />

      {!p ? (
        <Empty>{saved.error ?? "Loading the policy…"}</Empty>
      ) : (
        <>
          <Card icon={Layers3} title="Severity bands" subtitle="Relative to the limit in force at the reading (the vehicle's rule, a geofence's, or the night one)." flush>
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Band</th>
                    <th>When</th>
                    <th>Severity</th>
                  </tr>
                </thead>
                <tbody>
                  {bands.map((b) => (
                    <tr key={b.band}>
                      <td>
                        <strong>{b.band}</strong>
                      </td>
                      <td className="muted">{b.when}</td>
                      <td>
                        <Tag tone={b.tone}>{b.severity}</Tag>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Card title="Policy" subtitle="Saved in Redis (gps:config:speed-policy): configuration, it survives the demo reset.">
            <div className="form-grid">
              <label className="field">
                <span className="field-label">Breach from (km/h over)</span>
                <input className="input-box mono" type="number" min={0} value={p.breachOverKmph} onChange={(e) => set({ breachOverKmph: Number(e.target.value) })} />
              </label>
              <label className="field">
                <span className="field-label">Severe from (km/h over)</span>
                <input className="input-box mono" type="number" min={0} value={p.severeOverKmph} onChange={(e) => set({ severeOverKmph: Number(e.target.value) })} />
              </label>
              <label className="field">
                <span className="field-label">At night, limit lower by (km/h)</span>
                <input className="input-box mono" type="number" min={0} value={p.nightStricterKmph} onChange={(e) => set({ nightStricterKmph: Number(e.target.value) })} />
                <span className="field-hint">0 = the same limit day and night</span>
              </label>
              <label className="field">
                <span className="field-label">Night from (hour)</span>
                <input className="input-box mono" type="number" min={0} max={23} value={p.nightStartHour} onChange={(e) => set({ nightStartHour: Number(e.target.value) })} />
              </label>
              <label className="field">
                <span className="field-label">Night until (hour)</span>
                <input className="input-box mono" type="number" min={0} max={23} value={p.nightEndHour} onChange={(e) => set({ nightEndHour: Number(e.target.value) })} />
              </label>
            </div>
            <div className="switch-list">
              <label className="switch-row">
                <span>
                  <span className="switch-title">Do not alert the driver on SENSOR_SUSPECT</span>
                  <span className="switch-sub">The GPS said the truck was slower than its speedometer: a device-health matter, graded LOW</span>
                </span>
                <Switch checked={p.suppressSensorSuspect} onChange={(v) => set({ suppressSensorSuspect: v })} label="Suppress sensor-suspect alerts" />
              </label>
            </div>
            {note && <Callout tone={note.tone}>{note.text}</Callout>}
            <div className="card-actions">
              <button type="button" className="btn primary" onClick={save} disabled={busy}>
                <Save size={15} />
                {busy ? "Saving" : "Save policy"}
              </button>
            </div>
          </Card>
        </>
      )}

      <Card
        icon={MapPinned}
        title="Geofences with a speed limit"
        subtitle="Inside these, the stricter of the fence's limit and the vehicle's rule applies; the event names the fence. Set a limit on Processing → Geofencing → Live geofences."
        flush
      >
        {(zones.data ?? []).length === 0 ? (
          <Empty>No geofence has a speed limit yet (a plant's 20 km/h is set on the fence).</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Fence</th>
                  <th>Role</th>
                  <th className="right">Limit inside</th>
                </tr>
              </thead>
              <tbody>
                {(zones.data ?? []).map((z) => (
                  <tr key={z.id}>
                    <td>{z.name}</td>
                    <td className="muted">{z.role}</td>
                    <td className="right mono">{z.speedLimitKmph} km/h</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
