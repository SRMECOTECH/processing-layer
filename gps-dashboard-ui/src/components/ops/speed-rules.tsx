"use client";

import { useEffect, useState } from "react";
import { Building2, Globe, Plus, Save, SlidersHorizontal, Trash2, Truck } from "lucide-react";
import { Card, Empty, Tag } from "@/components/ops/kit";
import { formatNumber } from "@/lib/format";
import type { SpeedRule } from "@/lib/types";

export const RULE_FIELDS: { key: keyof SpeedRule; label: string; unit: string; step?: number; hint: string }[] = [
  { key: "limitKmph", label: "Speed limit", unit: "km/h", hint: "Speedometer above this counts" },
  { key: "minDurationSeconds", label: "Continuously over for", unit: "s", hint: "Before the episode is confirmed" },
  { key: "minConsecutivePings", label: "Consecutive pings over", unit: "pings", hint: "And at least this many readings" },
  { key: "hysteresisKmph", label: "Ends below limit minus", unit: "km/h", hint: "Stops flapping around the limit" },
  { key: "windowPings", label: "GPS speed over last", unit: "pings", hint: "Distance ÷ time window" },
  { key: "toleranceKmph", label: "Speedometer vs GPS tolerance", unit: "km/h", hint: "Allowed disagreement" },
  { key: "tolerancePercent", label: "… or share of speed", unit: "0–1", step: 0.01, hint: "Whichever is larger" },
  { key: "maxGapSeconds", label: "A gap longer than this breaks it", unit: "s", hint: "Missing pings reset the count" },
  { key: "mismatchPings", label: "Mismatch alert after", unit: "pings", hint: "Disagreeing pings in a row" },
];

export function scopeKind(scope: string): "default" | "tenant" | "vehicle" {
  return scope.startsWith("vehicle:") ? "vehicle" : scope.startsWith("tenant:") ? "tenant" : "default";
}

const KIND_ICON = { default: Globe, tenant: Building2, vehicle: Truck };
const KIND_ORDER = { vehicle: 0, tenant: 1, default: 2 };

/** Edits the speed rules in Redis (PUT/DELETE /api/speed/rules/{scope}); consumers reload them within 5 s. */
export function RuleEditor({ rules, onSaved }: { rules: SpeedRule[] | undefined; onSaved: () => void }) {
  const [scope, setScope] = useState("default");
  const [draft, setDraft] = useState<SpeedRule>();
  const [newKind, setNewKind] = useState<"tenant" | "vehicle">("tenant");
  const [newCode, setNewCode] = useState("");
  const [message, setMessage] = useState<string>();
  const [busy, setBusy] = useState(false);

  const current = rules?.find((r) => r.scope === scope);
  useEffect(() => {
    if (current) setDraft(current);
  }, [current?.scope, rules]); // eslint-disable-line react-hooks/exhaustive-deps

  async function save(rule: SpeedRule) {
    setBusy(true);
    setMessage(undefined);
    try {
      const response = await fetch(`/api/speed/rules/${encodeURIComponent(rule.scope)}`, {
        method: "PUT",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(rule),
      });
      if (!response.ok) throw new Error(`The service refused the rule (${response.status})`);
      setMessage(`Saved ${rule.scope}. Consumers pick it up within 5 seconds.`);
      setScope(rule.scope);
      setNewCode("");
      onSaved();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not save");
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    try {
      await fetch(`/api/speed/rules/${encodeURIComponent(scope)}`, { method: "DELETE" });
      setScope("default");
      onSaved();
      setMessage(`Removed ${scope}; those vehicles fall back to the next rule.`);
    } finally {
      setBusy(false);
    }
  }

  const sorted = [...(rules ?? [])].sort((a, b) => KIND_ORDER[scopeKind(a.scope)] - KIND_ORDER[scopeKind(b.scope)] || a.scope.localeCompare(b.scope));

  return (
    <Card icon={SlidersHorizontal} title="Rules in Redis" subtitle="gps:config:speed-rule:{scope} · pick a rule to edit it" monoSubtitle live>
      {!rules || !draft ? (
        <Empty>Loading the rules from Redis.</Empty>
      ) : (
        <div className="rule-editor">
          <div className="rule-scopes" role="listbox" aria-label="Rules">
            {sorted.map((r) => {
              const kind = scopeKind(r.scope);
              const Icon = KIND_ICON[kind];
              return (
                <button
                  key={r.scope}
                  type="button"
                  role="option"
                  aria-selected={r.scope === scope}
                  className="rule-scope"
                  onClick={() => setScope(r.scope)}
                >
                  <Icon size={15} className="rule-scope-icon" />
                  <span className="rule-scope-name mono">{r.scope.replace(/^(tenant|vehicle):/, "")}</span>
                  <span className="rule-scope-kind">{kind}</span>
                  <span className="rule-scope-limit mono">{formatNumber(r.limitKmph)}</span>
                </button>
              );
            })}
            <div className="rule-new">
              <div className="field-label">New rule for</div>
              <select className="select" value={newKind} onChange={(e) => setNewKind(e.target.value as "tenant" | "vehicle")}>
                <option value="tenant">A company (tenant code)</option>
                <option value="vehicle">A vehicle (registration)</option>
              </select>
              <input
                className="input-box mono"
                placeholder={newKind === "tenant" ? "hind-transport" : "JH05BS0128"}
                value={newCode}
                onChange={(e) => setNewCode(e.target.value)}
                aria-label="Tenant code or registration"
              />
              <button
                type="button"
                className="btn soft"
                disabled={busy || !newCode.trim()}
                onClick={() => save({ ...draft, scope: `${newKind}:${newCode.trim()}` })}
              >
                <Plus size={15} />
                Create from current values
              </button>
            </div>
          </div>

          <div className="rule-form">
            <div className="rule-form-head">
              <span className="mono rule-form-scope">{scope}</span>
              <Tag tone="neutral">{scopeKind(scope).toUpperCase()}</Tag>
            </div>
            <div className="form-grid">
              {RULE_FIELDS.map((f) => (
                <label className="field" key={f.key}>
                  <span className="field-label">{f.label}</span>
                  <span className="input-unit">
                    <input
                      className="input-box mono"
                      type="number"
                      step={f.step ?? 1}
                      value={Number(draft[f.key])}
                      onChange={(e) => setDraft({ ...draft, [f.key]: Number(e.target.value) })}
                    />
                    <span>{f.unit}</span>
                  </span>
                  <span className="field-hint">{f.hint}</span>
                </label>
              ))}
            </div>
            <label className="check">
              <input type="checkbox" checked={draft.requireGpsAgreement} onChange={(e) => setDraft({ ...draft, requireGpsAgreement: e.target.checked })} />
              Only count readings the GPS distance does not contradict
            </label>
            <div className="card-actions">
              <button type="button" className="btn primary" disabled={busy} onClick={() => save(draft)}>
                <Save size={15} />
                Save {scope}
              </button>
              {scope !== "default" && (
                <button type="button" className="btn danger-soft" disabled={busy} onClick={remove}>
                  <Trash2 size={15} />
                  Remove
                </button>
              )}
              {message && <span className="muted">{message}</span>}
            </div>
          </div>
        </div>
      )}
    </Card>
  );
}
