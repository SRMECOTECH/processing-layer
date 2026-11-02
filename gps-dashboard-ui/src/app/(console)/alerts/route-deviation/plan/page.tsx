"use client";

import { useState } from "react";
import { Map as MapIcon, Plus, Route, Trash2 } from "@/components/icons";
import { Callout, Card, Empty, KeyValues, Tag } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { RouteMap } from "@/components/routes/route-map";
import styles from "@/components/routes/routes.module.css";
import { ROLE_LABEL, type Fence } from "@/lib/geofence";
import { moduleById } from "@/lib/modules";
import { minutesText, type RoutePreview, type TripStop } from "@/lib/routes";
import { sendJson, usePoll } from "@/lib/use-poll";

const ROUTE = moduleById("route")!;

type PointInput = { mode: "fence" | "position"; placeId: string; position: string; name: string; stay: string };
type Vehicle = { vehicle: string; imei: string; tenant: string | null; active_plan_id: number | null };

const empty = (mode: PointInput["mode"]): PointInput => ({ mode, placeId: "", position: "", name: "", stay: "" });

/** "22.7868, 86.1857" or "22.7868 86.1857" -> [lat, lon] */
function parsePosition(text: string): [number, number] | null {
  const parts = text.split(/[\s,;]+/).filter(Boolean).map(Number);
  if (parts.length !== 2 || parts.some((n) => !Number.isFinite(n))) return null;
  return [parts[0], parts[1]];
}

function toPoint(p: PointInput, what: string) {
  const stayMinutes = p.stay ? Number(p.stay) : undefined;
  if (p.mode === "fence") {
    if (!p.placeId) throw new Error(`Choose the geofence for the ${what}`);
    return { placeId: Number(p.placeId), name: p.name || undefined, stayMinutes };
  }
  const position = parsePosition(p.position);
  if (!position) throw new Error(`Write the ${what} as latitude, longitude (for example 22.7868, 86.1857)`);
  return { latitude: position[0], longitude: position[1], name: p.name || undefined, stayMinutes };
}

/**
 * Plan a road route the way a navigation app does: a start, stops, a destination - each a geofence or a position.
 * Preview asks the routing engine (Valhalla on the HP, GraphHopper here as fallback) without keeping anything;
 * "Follow this truck on it" makes it the vehicle's plan, and the modules follow the truck against it from its next reading.
 */
export default function PlanRoutePage() {
  const { data: fences } = usePoll<Fence[]>("/api/geofences", 120_000);
  const { data: vehicles } = usePoll<Vehicle[]>("/api/routes/vehicles", 120_000);
  const [points, setPoints] = useState<PointInput[]>([empty("fence"), empty("fence")]);
  const [vehicle, setVehicle] = useState("");
  const [preview, setPreview] = useState<RoutePreview>();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string>();

  const sorted = [...(fences ?? [])].sort((a, b) => a.name.localeCompare(b.name));
  const role = (i: number) => (i === 0 ? "Start" : i === points.length - 1 ? "Destination" : `Stop ${i}`);

  function change(i: number, patch: Partial<PointInput>) {
    setPoints((ps) => ps.map((p, j) => (j === i ? { ...p, ...patch } : p)));
    setPreview(undefined);
  }

  function addStop() {
    setPoints((ps) => [...ps.slice(0, -1), empty("position"), ps[ps.length - 1]]);
    setPreview(undefined);
  }

  function removeStop(i: number) {
    setPoints((ps) => ps.filter((_, j) => j !== i));
    setPreview(undefined);
  }

  async function run(what: "preview" | "assign") {
    setProblem(undefined);
    setBusy(true);
    try {
      const all = points.map((p, i) => toPoint(p, role(i).toLowerCase()));
      if (what === "preview") {
        setPreview(await sendJson<RoutePreview>("/api/routes/preview", "POST", { points: all }));
      } else {
        if (!vehicle) throw new Error("Choose the vehicle to follow on this route");
        const created = await sendJson<{ planId: number }>("/api/routes", "POST", {
          vehicleNo: vehicle,
          origin: all[0],
          destination: all[all.length - 1],
          waypoints: all.slice(1, -1),
        });
        window.location.href = `/alerts/route-deviation/trip?id=${created.planId}`;
      }
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const start = preview?.points[0];
  const end = preview?.points[preview.points.length - 1];
  const chosen = vehicles?.find((v) => v.vehicle === vehicle);
  // the stops typed above, drawn on the preview
  const previewStops: TripStop[] = points.slice(1, -1).flatMap((p, i) => {
    const fence = p.mode === "fence" ? fences?.find((f) => String(f.id) === p.placeId) : undefined;
    const at = fence ? ([fence.latitude, fence.longitude] as [number, number]) : p.mode === "position" ? parsePosition(p.position) : null;
    if (!at) return [];
    return [
      {
        seq: i + 1,
        name: p.name || fence?.name || `Stop ${i + 1}`,
        kind: "MANUAL",
        placeId: fence?.id ?? null,
        latitude: at[0],
        longitude: at[1],
        status: "PENDING",
        stayMinutes: Number(p.stay || 0),
        arrivedAt: null,
        departedAt: null,
        missedAt: null,
        minutesInside: null,
        outOfOrder: false,
        progress: null,
        km: null,
      },
    ];
  });

  return (
    <div className="stack">
      <ModuleHeader
        module={ROUTE}
        title="Plan a route"
        description="Give a start and a destination - a geofence or a latitude, longitude - and stops in between. The routing engine plans the truck route on the all-India road map; then follow a vehicle on it: off-route, wrong direction, its live ETA and every stop."
      />

      <Card icon={Route} title="Start, stops, destination" subtitle="A geofence, or a position as latitude, longitude. Stay = minutes planned at a stop (used in the ETA).">
        <div className={styles.form}>
          {points.map((p, i) => (
            <div className={styles.pointRow} key={i}>
              <span className={styles.pointLabel}>{role(i)}</span>
              <select className="select" value={p.mode} onChange={(e) => change(i, { mode: e.target.value as PointInput["mode"] })} aria-label={`${role(i)} as`}>
                <option value="fence">Geofence</option>
                <option value="position">Latitude, longitude</option>
              </select>
              {p.mode === "fence" ? (
                <select className="select" value={p.placeId} onChange={(e) => change(i, { placeId: e.target.value })} aria-label={`${role(i)} geofence`}>
                  <option value="">Choose…</option>
                  {sorted.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.name} ({ROLE_LABEL[f.role] ?? f.role})
                    </option>
                  ))}
                </select>
              ) : (
                <input className="input-box" placeholder="22.7868, 86.1857" value={p.position} onChange={(e) => change(i, { position: e.target.value })} aria-label={`${role(i)} position`} />
              )}
              <input
                className="input-box"
                placeholder={i === 0 || i === points.length - 1 ? "name (optional)" : "stay min"}
                value={i === 0 || i === points.length - 1 ? p.name : p.stay}
                onChange={(e) => change(i, i === 0 || i === points.length - 1 ? { name: e.target.value } : { stay: e.target.value.replace(/[^0-9]/g, "") })}
                aria-label={i === 0 || i === points.length - 1 ? `${role(i)} name` : `${role(i)} planned stay in minutes`}
              />
              {i > 0 && i < points.length - 1 ? (
                <button type="button" className="btn soft" onClick={() => removeStop(i)} aria-label={`Remove ${role(i)}`}>
                  <Trash2 />
                </button>
              ) : (
                <span />
              )}
            </div>
          ))}
          <div className="toolbar-row">
            <button type="button" className="btn soft" onClick={addStop}>
              <Plus /> Add a stop
            </button>
            <button type="button" className="btn" onClick={() => run("preview")} disabled={busy}>
              {busy ? "Asking the routing engine…" : "Show the route"}
            </button>
          </div>
          {problem && <Callout tone="warn">{problem}</Callout>}
        </div>
      </Card>

      <div className="grid-main-side">
        <Card icon={MapIcon} title="The route" subtitle={preview ? `${preview.engine} · ${preview.costing} · answered in ${preview.millis} ms` : "Show the route to see it here."}>
          {preview && start && end ? (
            <RouteMap
              line={preview.points}
              stops={previewStops}
              origin={{ lat: start[0], lon: start[1], name: points[0].name || "Start" }}
              destination={{ lat: end[0], lon: end[1], name: points[points.length - 1].name || "Destination" }}
            />
          ) : (
            <Empty>No route yet.</Empty>
          )}
        </Card>
        <Card title="Follow a truck on it">
          {preview ? (
            <div className="stack">
              <KeyValues
                rows={[
                  ["Distance", <strong key="d">{preview.lengthKm} km</strong>],
                  ["Planned driving", minutesText(preview.minutes)],
                  ...preview.legs.map((l, i) => [`Leg ${i + 1}`, `${l.km} km · ${minutesText(l.minutes)}`] as [string, string]),
                  ["Geofences on the way", preview.geofencesOnTheWay.length ? preview.geofencesOnTheWay.map((f) => `${f.name} (km ${f.km})`).join(", ") : "none"],
                ]}
              />
              <select className="select" value={vehicle} onChange={(e) => setVehicle(e.target.value)} aria-label="Vehicle">
                <option value="">Vehicle…</option>
                {(vehicles ?? []).map((v) => (
                  <option key={v.vehicle} value={v.vehicle}>
                    {v.vehicle}
                    {v.active_plan_id ? " (has a route)" : ""}
                  </option>
                ))}
              </select>
              {chosen?.active_plan_id && <Callout tone="info">This replaces the route {chosen.vehicle} is following now.</Callout>}
              <button type="button" className="btn" onClick={() => run("assign")} disabled={busy || !vehicle}>
                Follow this truck on it
              </button>
              <span className="faint">
                Planning only reads the road map; following starts at the truck&apos;s next reading. <Tag tone="neutral">route-deviation</Tag> <Tag tone="neutral">waypoints</Tag>
              </span>
            </div>
          ) : (
            <Empty>Show the route first.</Empty>
          )}
        </Card>
      </div>
    </div>
  );
}
