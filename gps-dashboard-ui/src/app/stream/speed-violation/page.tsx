"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Gauge, ListChecks, Plus, Save, SlidersHorizontal, Trash2 } from "lucide-react";
import { EventTable } from "@/components/ops/events";
import { Card, Empty, PageTitle, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatDecimal, formatNumber } from "@/lib/format";
import type { ActiveSpeedEpisode, EventTypeCount, SpeedRule, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const FIELDS: { key: keyof SpeedRule; label: string; unit: string; step?: number }[] = [
  { key: "limitKmph", label: "Speed limit", unit: "km/h" },
  { key: "minDurationSeconds", label: "Over the limit at least", unit: "seconds" },
  { key: "minConsecutivePings", label: "Consecutive pings over", unit: "pings" },
  { key: "hysteresisKmph", label: "Ends below limit minus", unit: "km/h" },
  { key: "toleranceKmph", label: "Speedometer vs GPS tolerance", unit: "km/h" },
  { key: "tolerancePercent", label: "... or share of speed", unit: "0-1", step: 0.01 },
  { key: "windowPings", label: "GPS speed over last", unit: "pings" },
  { key: "maxGapSeconds", label: "A gap longer than this breaks it", unit: "seconds" },
  { key: "mismatchPings", label: "Mismatch alert after", unit: "pings" },
];

export default function SpeedViolationPage() {
  const rules = usePoll<SpeedRule[]>("/api/speed/rules", 10000);
  const active = usePoll<ActiveSpeedEpisode[]>("/api/speed/active", 3000);
  const events = usePoll<VehicleEventView[]>("/api/events?type=OVERSPEED,SPEED_SENSOR_MISMATCH&limit=30", 4000);
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const count = (type: string) => summary.data?.find((s) => s.type === type);
  const confirmed = (active.data ?? []).filter((a) => a.phase === "VIOLATING");

  return (
    <div className="stack">
      <PageTitle
        title="Speed violation"
        subtitle="Per-entity speed rules kept in Redis. A violation needs consecutive pings over the limit for the rule's duration; every ping's speedometer value is cross-checked against the speed worked out from latitude and longitude over the last few pings."
      />

      <div className="grid-4">
        <Tile label="Speeding right now" value={confirmed.length} tone={confirmed.length ? "bad" : undefined} big foot={`${(active.data?.length ?? 0) - confirmed.length} more over the limit, not confirmed yet`} />
        <Tile label="Over-speed events (24 h)" value={count("OVERSPEED")?.last24Hours ?? 0} big />
        <Tile label="Speedometer vs GPS mismatches" value={count("SPEED_SENSOR_MISMATCH")?.last24Hours ?? 0} big foot="Possible faulty or tampered sensor" />
        <Tile label="Rules in Redis" value={rules.data?.length} big foot="default, company and vehicle rules" />
      </div>

      <RuleEditor rules={rules.data} onSaved={rules.refresh} />

      <Card icon={Gauge} title="Episodes in progress" subtitle="Vehicles over their limit now (gps:state:speed:*). CANDIDATE becomes VIOLATING once the rule is met." live>
        {!active.data ? (
          <Empty>Waiting for the service.</Empty>
        ) : active.data.length === 0 ? (
          <Empty>No vehicle is over its limit right now.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Phase</th>
                  <th>Since</th>
                  <th className="right">Max</th>
                  <th className="right">Average</th>
                  <th className="right">GPS speed</th>
                  <th>Last check</th>
                  <th>Rule</th>
                </tr>
              </thead>
              <tbody>
                {active.data.map((a) => (
                  <tr key={a.imei} className={a.phase === "VIOLATING" ? "flagged" : undefined}>
                    <td>
                      <Link className="link mono" href={`/stream/tracking?imei=${a.imei}`}>
                        {a.imei}
                      </Link>
                    </td>
                    <td>
                      <Tag tone={a.phase === "VIOLATING" ? "bad" : "warn"}>{a.phase}</Tag>
                    </td>
                    <td className="muted">{formatAgo(a.episodeStartedAt)}</td>
                    <td className="right mono bad">{formatNumber(a.maxSpeedKmph)} km/h</td>
                    <td className="right mono">{formatDecimal(a.averageSpeedKmph)} km/h</td>
                    <td className="right mono">{a.lastGpsSpeedKmph === null ? "–" : `${formatDecimal(a.lastGpsSpeedKmph)} km/h`}</td>
                    <td>
                      <Tag tone={a.lastVerdict === "GPS_CONFIRMED" ? "good" : a.lastVerdict === "SENSOR_HIGH" ? "bad" : "neutral"}>
                        {a.lastVerdict.replace("_", " ")}
                      </Tag>
                    </td>
                    <td className="mono muted">
                      {a.ruleScope} · {formatNumber(a.limitKmph)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card icon={ListChecks} title="Recent over-speed and mismatch events" subtitle="Raised by consumer group gps-speed-violation-service" live>
        <EventTable events={events.data} empty="No over-speed yet: with one ping a minute a truck needs 3 pings in a row over the limit." />
      </Card>
    </div>
  );
}

function RuleEditor({ rules, onSaved }: { rules: SpeedRule[] | undefined; onSaved: () => void }) {
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

  return (
    <Card
      icon={SlidersHorizontal}
      title="Speed rules"
      subtitle="Looked up per reading as vehicle:{registration}, then tenant:{company}, then default (like Traccar's device, group, server attributes)."
      live
    >
      {!rules || !draft ? (
        <Empty>Loading the rules from Redis.</Empty>
      ) : (
        <>
          <div className="chips" style={{ marginBottom: 16 }}>
            {rules.map((r) => (
              <button key={r.scope} type="button" className="chip" aria-current={r.scope === scope ? "page" : undefined} onClick={() => setScope(r.scope)}>
                {r.scope} · {formatNumber(r.limitKmph)} km/h
              </button>
            ))}
          </div>
          <div className="form-grid">
            {FIELDS.map((f) => (
              <label className="field" key={f.key}>
                <span className="field-label">
                  {f.label} <span className="faint">({f.unit})</span>
                </span>
                <input
                  className="input-box mono"
                  type="number"
                  step={f.step ?? 1}
                  value={Number(draft[f.key])}
                  onChange={(e) => setDraft({ ...draft, [f.key]: Number(e.target.value) })}
                />
              </label>
            ))}
            <label className="check" style={{ alignSelf: "end", paddingBottom: 10 }}>
              <input type="checkbox" checked={draft.requireGpsAgreement} onChange={(e) => setDraft({ ...draft, requireGpsAgreement: e.target.checked })} />
              Only count readings the GPS does not contradict
            </label>
          </div>
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
          </div>
          <div className="inner" style={{ marginTop: 18 }}>
            <div className="inner-title">New rule for a company or a vehicle</div>
            <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
              <select className="select" value={newKind} onChange={(e) => setNewKind(e.target.value as "tenant" | "vehicle")}>
                <option value="tenant">Company (tenant code)</option>
                <option value="vehicle">Vehicle (registration)</option>
              </select>
              <input
                className="input-box mono"
                placeholder={newKind === "tenant" ? "for example hind-transport" : "for example JH05BS0128"}
                value={newCode}
                onChange={(e) => setNewCode(e.target.value)}
              />
              <button
                type="button"
                className="btn soft"
                disabled={busy || !newCode.trim()}
                onClick={() => save({ ...draft, scope: `${newKind}:${newCode.trim()}` })}
              >
                <Plus size={15} />
                Create with these values
              </button>
            </div>
          </div>
          {message && <p className="muted" style={{ marginTop: 12 }}>{message}</p>}
        </>
      )}
    </Card>
  );
}
