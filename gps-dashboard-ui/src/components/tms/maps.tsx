"use client";

import { Empty } from "@/components/ops/kit";
import { formatNumber } from "@/lib/format";
import type { Fence, FleetRow, TrackPoint } from "@/lib/tms";
import s from "./tms.module.css";

/**
 * Drawn to scale without map tiles (nothing loads from another host): an equirectangular projection fitted to what
 * is shown, with the fences as circles of their real radius.
 */
type Box = { minLat: number; maxLat: number; minLon: number; maxLon: number };

const W = 1000;

function fit(points: [number, number][], padMeters = 400): Box {
  const lats = points.map((p) => p[0]);
  const lons = points.map((p) => p[1]);
  const padLat = padMeters / 111_320;
  const midLat = (Math.min(...lats) + Math.max(...lats)) / 2;
  const padLon = padMeters / (111_320 * Math.cos((midLat * Math.PI) / 180));
  return { minLat: Math.min(...lats) - padLat, maxLat: Math.max(...lats) + padLat, minLon: Math.min(...lons) - padLon, maxLon: Math.max(...lons) + padLon };
}

function projector(box: Box, height: number) {
  // keep north-south and east-west at the same scale, so a circle stays a circle
  const midLat = ((box.minLat + box.maxLat) / 2) * (Math.PI / 180);
  const spanX = (box.maxLon - box.minLon) * Math.cos(midLat);
  const spanY = box.maxLat - box.minLat;
  const scale = Math.min((W - 40) / Math.max(spanX, 1e-6), (height - 40) / Math.max(spanY, 1e-6));
  const offsetX = (W - spanX * scale) / 2;
  const offsetY = (height - spanY * scale) / 2;
  return {
    x: (lon: number) => offsetX + (lon - box.minLon) * Math.cos(midLat) * scale,
    y: (lat: number) => height - offsetY - (lat - box.minLat) * scale,
    meters: (m: number) => (m / 111_320) * scale,
  };
}

function fencePoints(f: Fence | null): [number, number][] {
  if (!f) return [];
  const dLat = f.radiusMeters / 111_320;
  const dLon = f.radiusMeters / (111_320 * Math.cos((f.latitude * Math.PI) / 180));
  return [
    [f.latitude - dLat, f.longitude - dLon],
    [f.latitude + dLat, f.longitude + dLon],
  ];
}

/** A shipment: origin and destination fences, the truck's track during the shipment, where it is now. */
export function RouteMap({
  track,
  origin,
  destination,
  now,
  height = 420,
}: {
  track: TrackPoint[];
  origin: Fence | null;
  destination: Fence | null;
  now: { latitude: number; longitude: number } | null;
  height?: number;
}) {
  const points: [number, number][] = [
    ...track.map((p) => [p.latitude, p.longitude] as [number, number]),
    ...fencePoints(origin),
    ...fencePoints(destination),
    ...(now ? [[now.latitude, now.longitude] as [number, number]] : []),
  ];
  if (points.length === 0) return <Empty>No GPS readings for this shipment yet, and its places have no geofence.</Empty>;
  const H = Math.round(height * 1.6);
  const p = projector(fit(points), H);
  const path = track.map((t, i) => `${i === 0 ? "M" : "L"}${p.x(t.longitude).toFixed(1)},${p.y(t.latitude).toFixed(1)}`).join(" ");
  return (
    <div className={s.map} style={{ height }}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet" role="img" aria-label="Shipment route">
        {origin && (
          <g>
            <circle className={s.fenceOrigin} cx={p.x(origin.longitude)} cy={p.y(origin.latitude)} r={Math.max(6, p.meters(origin.radiusMeters))} />
            <text className={s.mapLabel} x={p.x(origin.longitude) + 8} y={p.y(origin.latitude) - 8}>
              {origin.name}
            </text>
          </g>
        )}
        {destination && (
          <g>
            <circle
              className={s.fenceDestination}
              cx={p.x(destination.longitude)}
              cy={p.y(destination.latitude)}
              r={Math.max(6, p.meters(destination.radiusMeters))}
            />
            <text className={s.mapLabel} x={p.x(destination.longitude) + 8} y={p.y(destination.latitude) - 8}>
              {destination.name}
            </text>
          </g>
        )}
        {track.length > 1 && <path className={s.track} d={path} />}
        {track.length > 0 && <circle className={s.trackStart} cx={p.x(track[0].longitude)} cy={p.y(track[0].latitude)} r={6} />}
        {now && (
          <g>
            <circle className={s.truckHalo} cx={p.x(now.longitude)} cy={p.y(now.latitude)} r={18} />
            <circle className={s.truck} cx={p.x(now.longitude)} cy={p.y(now.latitude)} r={8} />
          </g>
        )}
      </svg>
      <div className={s.mapLegend}>
        <span>
          <i className={s.legendTrack} />
          Track ({formatNumber(track.length)} readings)
        </span>
        <span>
          <i className={s.legendOrigin} />
          Origin fence
        </span>
        <span>
          <i className={s.legendDestination} />
          Destination fence
        </span>
        <span>
          <i className={s.legendTruck} />
          Truck now
        </span>
      </div>
    </div>
  );
}

/** A fleet: one dot per truck with a position; moving trucks in the accent colour, offline ones amber. */
export function FleetMap({ rows, height = 380, onPick }: { rows: FleetRow[]; height?: number; onPick?: (row: FleetRow) => void }) {
  const placed = rows.filter((r) => r.latitude !== null && r.longitude !== null);
  if (placed.length === 0) return <Empty>No truck has reported a position yet.</Empty>;
  const H = Math.round(height * 1.6);
  const p = projector(fit(placed.map((r) => [r.latitude!, r.longitude!]), 1500), H);
  return (
    <div className={s.map} style={{ height }}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet" role="img" aria-label="Fleet positions">
        {placed.map((r) => (
          <circle
            key={r.vehicleNo}
            className={`${s.dot} ${r.state === "STANDING" ? s.dotStanding : r.state === "OFFLINE" ? s.dotOffline : ""}`}
            cx={p.x(r.longitude!)}
            cy={p.y(r.latitude!)}
            r={r.state === "MOVING" ? 8 : 6.5}
            onClick={() => onPick?.(r)}
          >
            <title>{`${r.vehicleNo}: ${r.state.toLowerCase()}${r.speedKmph !== null ? `, ${formatNumber(r.speedKmph)} km/h` : ""}`}</title>
          </circle>
        ))}
      </svg>
      <div className={s.mapLegend}>
        <span>
          <i className={s.legendTrack} />
          Moving
        </span>
        <span>
          <i style={{ background: "var(--text-3)" }} />
          Standing
        </span>
        <span>
          <i style={{ background: "var(--warn)" }} />
          Offline (no reading for 30 min)
        </span>
      </div>
    </div>
  );
}
