"use client";

import { useMemo, useState } from "react";
import { Grid3x3, MapPinned, PenLine, Timer, Truck, Upload } from "@/components/icons";
import { Callout, Card, Empty, Stats, Tag, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader, SettingsCard } from "@/components/ops/module";
import { ROLE_LABEL, SILENT_AFTER_MINUTES, roleTone, stay, vehicleOf, type Fence, type FenceRole, type GeofenceEngine, type Visit } from "@/lib/geofence";
import { formatDateTime } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import { post, sendJson, usePoll } from "@/lib/use-poll";

const GEOFENCE = moduleById("geofence")!;
const ROLES: FenceRole[] = ["ORIGIN", "DESTINATION", "YARD", "CHECKPOINT", "ZONE"];

type ImportReport = { read: number; added: number; updated: number; keptBecauseEditedHere: string[]; refused: string[] };

/** Live geofences: who is inside which fence now, and the fence master the module checks every reading against. */
export default function LiveGeofencesPage() {
  const fences = usePoll<Fence[]>("/api/geofences", 15_000);
  const live = usePoll<Visit[]>("/api/geofences/live?limit=500", 5_000);
  const engine = usePoll<GeofenceEngine>("/api/geofences/engine", 60_000);
  const [editing, setEditing] = useState<Fence | "new" | null>(null);
  const [imported, setImported] = useState<ImportReport | string | null>(null);

  const active = (fences.data ?? []).filter((f) => f.active);
  const reporting = (live.data ?? []).filter((v) => v.silentMinutes <= SILENT_AFTER_MINUTES);
  const silent = new Set((live.data ?? []).filter((v) => v.silentMinutes > SILENT_AFTER_MINUTES).map((v) => v.imei)).size;
  const over = reporting.filter((v) => v.dwellAlertAt);

  async function importSmartTruck() {
    try {
      setImported(await post<ImportReport>("/api/geofences/import/smart-truck"));
      fences.refresh();
    } catch (problem) {
      setImported(problem instanceof Error ? problem.message : "Import failed");
    }
  }

  return (
    <div className="stack">
      <ModuleHeader
        module={GEOFENCE}
        title="Live geofences"
        description="Every reading of every vehicle is checked against every active fence through a grid index: one hash lookup finds the few fences near the reading, an exact circle or polygon test decides, and a visit only counts once it held 15 minutes (the smart_truck rule)."
        actions={
          <>
            <button type="button" className="btn soft" onClick={importSmartTruck}>
              <Upload size={15} />
              Copy from smart_truck
            </button>
            <button type="button" className="btn primary" onClick={() => setEditing("new")}>
              New fence
            </button>
          </>
        }
      />

      {imported && (
        <Callout tone={typeof imported === "string" ? "warn" : "info"} title="smart_truck geofences">
          {typeof imported === "string"
            ? imported
            : `${imported.read} read: ${imported.added} added, ${imported.updated} updated${imported.keptBecauseEditedHere.length ? `, ${imported.keptBecauseEditedHere.length} kept because they were edited here` : ""}${imported.refused.length ? `, ${imported.refused.length} refused (${imported.refused.join(", ")})` : ""}.`}
        </Callout>
      )}

      <Stats columns={4}>
        <Tile label="Active fences" icon={MapPinned} value={active.length} note={`${(fences.data ?? []).length - active.length} switched off`} big />
        <Tile
          label="Vehicles inside now"
          icon={Truck}
          value={new Set(reporting.map((v) => v.imei)).size}
          note={`${reporting.length} open visits${silent ? ` · ${silent} more inside when last heard` : ""}`}
          big
        />
        <Tile label="Over the dwell limit" icon={Timer} value={over.length} tone={over.length ? "warn" : undefined} note="still inside" big />
        <Tile
          label="Grid cells"
          icon={Grid3x3}
          value={engine.data?.index.filledCells}
          note={engine.data ? `${engine.data.index.cellKmApprox} km cells · up to ${engine.data.index.maxFencesPerCell} fences in one` : "…"}
          big
        />
      </Stats>

      {editing && <FenceEditor fence={editing === "new" ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); fences.refresh(); }} />}

      <Card
        icon={Truck}
        title="Inside a fence now"
        subtitle="Open visits, longest first: from the first reading inside to the vehicle's last reading (a tracker silent for 30 min shows 'No signal')."
        live={!live.error}
        flush
      >
        {(live.data ?? []).length === 0 ? (
          <Empty>{live.error ?? "No vehicle is inside a fence right now."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th>Fence</th>
                  <th>Since</th>
                  <th className="right">Stay so far</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {(live.data ?? []).slice(0, 200).map((v) => (
                  <tr key={v.id}>
                    <td className="mono">{vehicleOf(v)}</td>
                    <td>
                      {v.placeName} <Tag tone={roleTone(v.placeRole)}>{ROLE_LABEL[v.placeRole] ?? v.placeRole}</Tag>
                    </td>
                    <td className="mono">
                      {formatDateTime(v.enteredAt)}
                      {(v.entryGapSeconds ?? 0) > 3600 && <span className="faint" title="GPS gap before the entry"> ±{Math.round((v.entryGapSeconds ?? 0) / 60)} min</span>}
                    </td>
                    <td className="right mono">{stay(v.minutes)}</td>
                    <td>
                      {v.silentMinutes > SILENT_AFTER_MINUTES ? (
                        <Tag tone="neutral">No signal {stay(v.silentMinutes)}</Tag>
                      ) : v.dwellAlertAt ? (
                        <Tag tone="bad">Over limit</Tag>
                      ) : (
                        <Tag tone="good">Inside</Tag>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <FenceTable fences={fences.data ?? []} error={fences.error} onEdit={setEditing} onToggled={fences.refresh} />

      {engine.data && (
        <SettingsCard
          title="Detection rules"
          subtitle="The smart_truck rules, so live visits agree with tta_trip_geofence_events. Changed in application.yml (gps.modules.geofence.*)."
          source="gps.modules.geofence"
          live
          settings={[
            { label: "A visit counts after", value: engine.data.rules.confirmMinutes, unit: "min", hint: "Shorter is a pass-through" },
            { label: "Exit at once beyond", value: `${engine.data.rules.escapeFactor}×`, unit: "radius", hint: "Far out is not GPS scatter" },
            { label: "Pass-through needs", value: engine.data.rules.passMinTravelMeters, unit: "m moved", hint: "Less is scatter at the border" },
            { label: "Crossings inferred up to", value: engine.data.rules.crossingMaxGapMinutes, unit: "min gap", hint: "Fences jumped between two readings" },
            { label: "Dwell alert · plant", value: engine.data.rules.dwellAlertMinutesByRole.ORIGIN, unit: "min" },
            { label: "Dwell alert · customer", value: engine.data.rules.dwellAlertMinutesByRole.DESTINATION, unit: "min" },
            { label: "Dwell alert · yard", value: engine.data.rules.dwellAlertMinutesByRole.YARD, unit: "min" },
            { label: "Dwell alert · other", value: engine.data.rules.defaultDwellAlertMinutes, unit: "min", hint: "A fence's own limit wins" },
          ]}
        />
      )}

      <HowItWorks module={GEOFENCE} />
    </div>
  );
}

function FenceTable({ fences, error, onEdit, onToggled }: { fences: Fence[]; error?: string; onEdit: (f: Fence) => void; onToggled: () => void }) {
  const [q, setQ] = useState("");
  const [role, setRole] = useState<string>("");
  const rows = useMemo(
    () =>
      fences
        .filter((f) => !role || f.role === role)
        .filter((f) => !q || `${f.code} ${f.name}`.toLowerCase().includes(q.toLowerCase()))
        .sort((a, b) => b.insideNow - a.insideNow || a.code.localeCompare(b.code)),
    [fences, q, role],
  );

  async function toggle(f: Fence) {
    await post(`/api/geofences/${f.id}/active?value=${!f.active}`);
    onToggled();
  }

  return (
    <Card
      icon={MapPinned}
      title="Fences"
      subtitle="The place table: copied from smart_truck (anchor = medoid of pings at the site, gazetteer = a town centre standing in for an unknown site, plant = surveyed), or drawn here."
      flush
      actions={
        <div className="toolbar">
          <input className="input-box" placeholder="Search fences" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search fences" />
          <select className="select" value={role} onChange={(e) => setRole(e.target.value)} aria-label="Role">
            <option value="">All roles</option>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABEL[r]}
              </option>
            ))}
          </select>
        </div>
      }
    >
      {rows.length === 0 ? (
        <Empty>{error ?? "No fence matches."}</Empty>
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Fence</th>
                <th>Role</th>
                <th>Shape</th>
                <th className="right">Inside now</th>
                <th className="right">Dwell limit</th>
                <th className="right">Speed limit</th>
                <th>Source</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((f) => (
                <tr key={f.id} className={f.active ? undefined : "faint"}>
                  <td>
                    <strong>{f.name}</strong>
                    {f.name !== f.code && <div className="muted small mono">{f.code}</div>}
                  </td>
                  <td>
                    <Tag tone={roleTone(f.role)}>{ROLE_LABEL[f.role] ?? f.role}</Tag>
                  </td>
                  <td className="mono small">
                    {f.shape === "POLYGON" ? `polygon · ${f.polygon?.length ?? 0} corners` : `circle · ${(f.radiusMeters / 1000).toFixed(f.radiusMeters < 10_000 ? 2 : 0)} km`}
                  </td>
                  <td className="right mono">{f.insideNow || <span className="faint">0</span>}</td>
                  <td className="right mono">
                    {f.dwellAlertMinutesInForce ? stay(f.dwellAlertMinutesInForce) : <span className="faint">off</span>}
                    {f.dwellAlertMinutes === null && f.dwellAlertMinutesInForce ? <span className="faint"> (role)</span> : null}
                  </td>
                  <td className="right mono">{f.speedLimitKmph ? `${f.speedLimitKmph} km/h` : <span className="faint">–</span>}</td>
                  <td className="small">
                    {f.source === "SMART_TRUCK" ? `smart_truck · ${f.sourceDetail ?? ""}` : "drawn here"}
                    {f.tripsSeen ? <span className="faint"> · {f.tripsSeen} trips</span> : null}
                  </td>
                  <td className="right">
                    <button type="button" className="icon-button" title="Edit" aria-label={`Edit ${f.name}`} onClick={() => onEdit(f)}>
                      <PenLine size={15} />
                    </button>
                    <button type="button" className="text-button" onClick={() => toggle(f)}>
                      {f.active ? "Switch off" : "Switch on"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function FenceEditor({ fence, onClose, onSaved }: { fence: Fence | null; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(fence?.name ?? "");
  const [role, setRole] = useState<FenceRole>(fence?.role ?? "DESTINATION");
  const [shape, setShape] = useState<"CIRCLE" | "POLYGON">(fence?.shape ?? "CIRCLE");
  const [lat, setLat] = useState(fence ? String(fence.latitude) : "");
  const [lon, setLon] = useState(fence ? String(fence.longitude) : "");
  const [radius, setRadius] = useState(fence ? String(fence.radiusMeters) : "1000");
  const [corners, setCorners] = useState(fence?.polygon ? JSON.stringify(fence.polygon) : "");
  const [dwell, setDwell] = useState(fence?.dwellAlertMinutes === null || fence?.dwellAlertMinutes === undefined ? "" : String(fence.dwellAlertMinutes));
  const [speed, setSpeed] = useState(fence?.speedLimitKmph ? String(fence.speedLimitKmph) : "");
  const [problem, setProblem] = useState<string>();
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setProblem(undefined);
    try {
      let polygon: [number, number][] | null = null;
      if (shape === "POLYGON") {
        polygon = JSON.parse(corners) as [number, number][];
      }
      await sendJson(`/api/geofences/${encodeURIComponent(fence?.code ?? name)}`, "PUT", {
        name,
        role,
        shape,
        latitude: shape === "CIRCLE" ? Number(lat) : null,
        longitude: shape === "CIRCLE" ? Number(lon) : null,
        radiusMeters: shape === "CIRCLE" ? Number(radius) : null,
        polygon,
        dwellAlertMinutes: dwell === "" ? null : Number(dwell),
        speedLimitKmph: speed === "" ? null : Number(speed),
      });
      onSaved();
    } catch (e) {
      setProblem(e instanceof SyntaxError ? "Corners must be JSON: [[22.81, 86.07], [22.82, 86.08], [22.80, 86.09]]" : e instanceof Error ? e.message : "Not saved");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card icon={PenLine} title={fence ? `Edit ${fence.name}` : "New fence"} subtitle="Saved fences are marked as drawn here: a later copy from smart_truck leaves them alone. The module uses the change at once.">
      <div className="form-grid">
        <label className="field">
          <span className="field-label">Name</span>
          <input className="input-box" value={name} onChange={(e) => setName(e.target.value)} disabled={!!fence} />
        </label>
        <label className="field">
          <span className="field-label">Role</span>
          <select className="select" value={role} onChange={(e) => setRole(e.target.value as FenceRole)} disabled={!!fence}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {ROLE_LABEL[r]}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field-label">Shape</span>
          <select className="select" value={shape} onChange={(e) => setShape(e.target.value as "CIRCLE" | "POLYGON")}>
            <option value="CIRCLE">Circle</option>
            <option value="POLYGON">Polygon</option>
          </select>
        </label>
        {shape === "CIRCLE" ? (
          <>
            <label className="field">
              <span className="field-label">Latitude</span>
              <input className="input-box mono" value={lat} onChange={(e) => setLat(e.target.value)} placeholder="22.788598" />
            </label>
            <label className="field">
              <span className="field-label">Longitude</span>
              <input className="input-box mono" value={lon} onChange={(e) => setLon(e.target.value)} placeholder="86.199600" />
            </label>
            <label className="field">
              <span className="field-label">Radius (m)</span>
              <input className="input-box mono" value={radius} onChange={(e) => setRadius(e.target.value)} />
              <span className="field-hint">50 m to 50 km</span>
            </label>
          </>
        ) : (
          <label className="field" style={{ gridColumn: "1 / -1" }}>
            <span className="field-label">Corners, in order around the border</span>
            <textarea className="input-box mono" rows={3} value={corners} onChange={(e) => setCorners(e.target.value)} placeholder="[[22.81, 86.07], [22.82, 86.08], [22.80, 86.09]]" />
            <span className="field-hint">[latitude, longitude] pairs; the border closes by itself. Inside is decided by ray casting.</span>
          </label>
        )}
        <label className="field">
          <span className="field-label">Dwell alert after (min)</span>
          <input className="input-box mono" value={dwell} onChange={(e) => setDwell(e.target.value)} placeholder="role default" />
          <span className="field-hint">Empty = the role&apos;s default, 0 = never</span>
        </label>
        <label className="field">
          <span className="field-label">Speed limit inside (km/h)</span>
          <input className="input-box mono" value={speed} onChange={(e) => setSpeed(e.target.value)} placeholder="none" />
          <span className="field-hint">Used by the speed module: the stricter of this and the vehicle&apos;s rule</span>
        </label>
      </div>
      {problem && <Callout tone="warn" title="Not saved">{problem}</Callout>}
      <div className="card-actions">
        <button type="button" className="btn soft" onClick={onClose}>
          Cancel
        </button>
        <button type="button" className="btn primary" onClick={save} disabled={busy}>
          {busy ? "Saving" : "Save fence"}
        </button>
      </div>
    </Card>
  );
}
