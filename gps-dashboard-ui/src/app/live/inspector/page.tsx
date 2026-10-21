"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";
import { Crosshair, Database, History, ListChecks, Workflow } from "lucide-react";
import { AreaChart } from "@/components/ops/charts";
import { EventTable } from "@/components/ops/events";
import { Card, Empty, KeyValues, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatCoordinate, formatDecimal, formatDistance, formatDuration, formatNumber, formatTime } from "@/lib/format";
import type { CacheEntry, VehicleEventView, VehicleTrackingState } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function TrackingPage() {
  return (
    <Suspense fallback={null}>
      <Tracking />
    </Suspense>
  );
}

type VehicleRow = Record<string, string>;

function Tracking() {
  const params = useSearchParams();
  const router = useRouter();
  const [query, setQuery] = useState("");
  const states = usePoll<VehicleRow[]>("/api/vehicles/states?limit=500", 4000);

  const list = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (states.data ?? [])
      .filter((v) => !q || `${v.imei} ${v.vehicleRegistrationNo}`.toLowerCase().includes(q))
      .sort((a, b) => (a.vehicleRegistrationNo || a.imei).localeCompare(b.vehicleRegistrationNo || b.imei));
  }, [states.data, query]);

  const selected = params.get("imei") ?? list[0]?.imei ?? null;

  return (
    <div className="stack">
      <PageHeader
        title="Vehicle inspector"
        description="Pick a vehicle to see everything the platform keeps for it: each processor's state in Redis, its recent history, its events, and the processing and trip-detection state."
      />
      <div className="tracking">
        <aside className="card tracking-list">
          <input
            className="input-box"
            type="search"
            placeholder="Vehicle or IMEI"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Find a vehicle"
          />
          <div className="tracking-count faint">{states.data ? `${list.length} vehicles` : "Loading"}</div>
          <div className="tracking-rows">
            {list.map((v) => (
              <button
                key={v.imei}
                type="button"
                className={`tracking-row${v.imei === selected ? " active" : ""}`}
                onClick={() => router.replace(`/live/inspector?imei=${v.imei}`, { scroll: false })}
              >
                <span className={`dot ${v.moving === "true" ? "good" : "muted"}`} />
                <span className="tracking-row-main">
                  <span className="mono">{v.vehicleRegistrationNo || "Unassigned"}</span>
                  <span className="faint mono" style={{ fontSize: 11 }}>
                    {v.imei}
                  </span>
                </span>
                <span className="tracking-row-side mono">
                  {v.speedKmph} km/h
                  <span className="faint">{formatAgo(v.lastRecordedAt)}</span>
                </span>
              </button>
            ))}
          </div>
        </aside>
        <div className="stack">{selected ? <VehicleDetail imei={selected} /> : <Empty>No vehicles yet. Start the fleet.</Empty>}</div>
      </div>
    </div>
  );
}

const KEY_TABS: [string, string][] = [
  ["vehicle", "Vehicle state"],
  ["live", "Live position"],
  ["speed", "Speed"],
  ["ignition", "Ignition"],
  ["stoppage", "Stoppage"],
];

/** The Redis key prefix of each tab (see RedisKeyNames). */
const KEY_PREFIX: Record<string, string> = {
  vehicle: "gps:state:vehicle:",
  live: "gps:live:vehicle:",
  speed: "gps:state:speed:",
  ignition: "gps:state:ignition:",
  stoppage: "gps:state:stoppage:",
};

function VehicleDetail({ imei }: { imei: string }) {
  const state = usePoll<VehicleTrackingState>(`/api/vehicles/${encodeURIComponent(imei)}/state?history=60`, 3000);
  const [tab, setTab] = useState("vehicle");
  const [raw, setRaw] = useState(false);

  const s = state.data;
  const entry = (part: string) => s?.cache.find((c) => c.key.startsWith(KEY_PREFIX[part]));
  const hash = (part: string) => (entry(part)?.value ?? {}) as Record<string, string>;
  const vehicle = hash("vehicle");
  const live = hash("live");
  const ignition = hash("ignition");
  const speed = hash("speed");
  const stoppage = hash("stoppage");
  const shown = entry(tab);

  const history = [...(s?.history ?? [])].reverse();
  const events: VehicleEventView[] = (s?.events ?? []).map((e, i) => ({ ...(e as unknown as VehicleEventView), id: i, updatedAt: String(e.detectedAt ?? "") }));

  if (!s) return <Empty>{state.error ?? "Loading the vehicle's state."}</Empty>;

  return (
    <>
      <Card
        icon={Crosshair}
        title={vehicle.vehicleRegistrationNo || live.vehicleRegistrationNo || imei}
        subtitle={`${imei}${vehicle.tenantCode ? ` · ${vehicle.tenantCode}` : ""} · last reading ${formatTime(vehicle.lastRecordedAt)} (${formatAgo(vehicle.lastRecordedAt)})`}
        monoSubtitle
        live
        badges={
          <>
            <Tag tone={vehicle.moving === "true" ? "good" : "neutral"}>{vehicle.moving === "true" ? "MOVING" : "STANDING"}</Tag>
            {ignition.ignitionOn && (
              <Tag tone={ignition.ignitionOn === "true" ? "accent" : "neutral"}>
                IGNITION {ignition.ignitionOn === "true" ? "ON" : "OFF"} · {ignition.source}
              </Tag>
            )}
            {speed.phase && speed.phase !== "NORMAL" && <Tag tone="bad">SPEED {speed.phase}</Tag>}
            {stoppage.phase === "STOPPED" && <Tag tone={stoppage.kind === "PARKED" ? "neutral" : "warn"}>{stoppage.kind}</Tag>}
          </>
        }
      >
        <Stats>
          <Tile label="Speed" value={Number(vehicle.speedKmph || 0)} unit="km/h" foot={`Kalman ${formatDecimal(Number(vehicle.kalmanSpeedKmph || 0))} km/h`} />
          <Tile label="Heading" value={vehicle.headingDegrees ? `${Math.round(Number(vehicle.headingDegrees))}°` : "–"} foot={vehicle.headingDegrees ? compass(Number(vehicle.headingDegrees)) : "not moved yet"} />
          <Tile label="Distance (Traccar rules)" value={formatDistance(Number(vehicle.totalDistanceMeters || 0))} foot={`Kalman track ${formatDistance(Number(vehicle.kalmanDistanceMeters || 0))}`} />
          <Tile label="Engine time" value={formatDuration(Number(ignition.engineSeconds || 0))} foot={`idling ${formatDuration(Number(ignition.idleSecondsTotal || 0))}`} />
        </Stats>
        <Stats>
          <Tile label="Readings" value={Number(vehicle.readings || 0)} foot={`every ${formatDecimal(Number(vehicle.averageIntervalSeconds || 0))} s on average`} />
          <Tile label="Odometer" value={vehicle.odometerMeters ? formatDistance(Number(vehicle.odometerMeters)) : "–"} foot={`${vehicle.odometerReadings || 0} readings used it`} />
          <Tile label="Jitter clamped" value={Number(vehicle.jitterFilteredReadings || 0)} foot={`${vehicle.jumpFilteredReadings || 0} jumps ignored, ${vehicle.outOfOrderReadings || 0} late`} />
          <Tile label="Time moving" value={formatDuration(Number(vehicle.movingSeconds || 0))} foot={`standing ${formatDuration(Number(vehicle.standingSeconds || 0))}`} />
        </Stats>
      </Card>

      <Card
        icon={Database}
        title="Cache in Redis"
        subtitle="Each consumer keeps its own state for this vehicle; this is exactly what is stored."
        live
        actions={
          <button type="button" className="text-button" onClick={() => setRaw(!raw)} aria-pressed={raw}>
            {raw ? "Show as table" : "Show raw JSON"}
          </button>
        }
      >
        <div style={{ marginBottom: 14 }}>
          <Segmented label="Redis key" value={tab} onChange={setTab} options={KEY_TABS} />
        </div>
        {shown ? <CacheView entry={shown} raw={raw} /> : <Empty>Nothing cached.</Empty>}
        <div className="card-foot">
          <span className="mono">
            {s.cache.map((c) => `${c.key.replace(`:${imei}`, "")} (${c.type})`).join(" · ")}
          </span>
        </div>
      </Card>

      <Card icon={History} title="History (Redis stream)" subtitle={`gps:history:${imei} · newest ${history.length} readings, oldest first`} monoSubtitle live>
        {history.length < 2 ? (
          <Empty>Not enough history yet.</Empty>
        ) : (
          <>
            <AreaChart
              unit="km/h"
              averageSeconds={1}
              series={[
                { name: "Speedometer", values: history.map((h) => Number(h.speedKmph)), strong: true },
                { name: "Kalman", values: history.map((h) => Number(h.kalmanSpeedKmph)) },
              ]}
              axis={[formatTime(history[0].recordedAt), "", formatTime(history[history.length - 1].recordedAt)]}
              pointLabel={(i) => formatTime(history[i]?.recordedAt)}
            />
            <div className="data-table-wrap" style={{ marginTop: 16 }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Recorded</th>
                    <th className="right">Speed</th>
                    <th>State</th>
                    <th>Ignition</th>
                    <th className="right">Distance</th>
                    <th>Source</th>
                    <th className="right">From position</th>
                    <th className="right">Gap</th>
                    <th>Position</th>
                  </tr>
                </thead>
                <tbody>
                  {[...history].reverse().slice(0, 25).map((h) => (
                    <tr key={h.id}>
                      <td className="mono">{formatTime(h.recordedAt)}</td>
                      <td className="right mono">{h.speedKmph} km/h</td>
                      <td>{h.movement === "MOVING" ? <Tag tone="good">MOVING</Tag> : <Tag tone="neutral">STOPPED</Tag>}</td>
                      <td className="muted">{h.ignition === "" ? "–" : h.ignition === "true" ? "on" : "off"}</td>
                      <td className="right mono">{formatDistance(Number(h.distanceMeters))}</td>
                      <td className="mono faint" style={{ fontSize: 11.5 }}>
                        {h.distanceSource}
                      </td>
                      <td className="right mono muted">{h.impliedSpeedKmph ? `${h.impliedSpeedKmph} km/h` : "–"}</td>
                      <td className="right mono muted">{formatDuration(Number(h.secondsSincePrevious))}</td>
                      <td className="mono faint">{formatCoordinate(h.latitude, h.longitude)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Card>

      <Card icon={ListChecks} title="Events" subtitle={`gps:events:${imei} · latest 20`} monoSubtitle live>
        <EventTable events={events} empty="No events for this vehicle yet." />
      </Card>

      <Card icon={Workflow} title="Processing and trip detection" subtitle="What the other stages hold for this vehicle." live>
        <div className="grid-2">
          <div className="inner">
            <div className="inner-title">Processing step memory (previous reading)</div>
            {s.processing ? (
              <KeyValues
                rows={[
                  ["Recorded", <span className="mono" key="r">{formatTime(s.processing.recordedAt)}</span>],
                  ["Position", <span className="mono" key="p">{formatCoordinate(s.processing.latitude, s.processing.longitude)}</span>],
                  ["Total distance", formatDistance(s.processing.totalDistanceMeters)],
                  ["Odometer", s.processing.odometerMeters ? formatDistance(s.processing.odometerMeters) : "–"],
                ]}
              />
            ) : (
              <Empty>Not in memory (read from PostgreSQL on its next reading).</Empty>
            )}
          </div>
          <div className="inner">
            <div className="inner-title">Trip detector (vehicle_motion_state)</div>
            {s.tripMotion ? (
              <KeyValues
                rows={[
                  ["Motion", s.tripMotion.motion_streak ? <Tag key="m" tone="good">ON A TRIP</Tag> : <Tag key="m" tone="neutral">STOPPED</Tag>],
                  ["Since", <span className="mono" key="s">{formatTime(String(s.tripMotion.event_recorded_at))}</span>],
                  ["Newest reading", <span className="mono" key="n">{formatTime(String(s.tripMotion.last_recorded_at))}</span>],
                  ["Readings in window", formatNumber(Array.isArray(s.tripMotion.reading_window) ? s.tripMotion.reading_window.length : 0)],
                ]}
              />
            ) : (
              <Empty>No trip state yet.</Empty>
            )}
          </div>
        </div>
      </Card>
    </>
  );
}

function CacheView({ entry, raw }: { entry: CacheEntry; raw: boolean }) {
  const fields = Object.entries(entry.value ?? {});
  return (
    <div>
      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginBottom: 10 }}>
        <span className="mono" style={{ fontWeight: 600 }}>
          {entry.key}
        </span>
        <Tag tone="neutral">{entry.type.toUpperCase()}</Tag>
        <span className="faint" style={{ fontSize: 12.5 }}>
          {entry.purpose}
        </span>
      </div>
      {fields.length === 0 ? (
        <Empty>This key does not exist yet for the vehicle.</Empty>
      ) : raw ? (
        <pre className="code" style={{ maxHeight: 420, overflow: "auto" }}>
          {JSON.stringify(entry.value, null, 2)}
        </pre>
      ) : (
        <div className="cache-grid">
          {fields.map(([field, value]) => (
            <div className="cache-field" key={field}>
              <span className="cache-name">{field}</span>
              <span className="cache-value mono">{String(value) === "" ? <span className="faint">empty</span> : String(value)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function compass(degrees: number): string {
  const points = ["north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"];
  return `heading ${points[Math.round(degrees / 45) % 8]}`;
}
