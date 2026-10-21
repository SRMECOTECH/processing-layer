"use client";

import { useRouter } from "next/navigation";
import { formatNumber } from "@/lib/format";
import type { LiveVehicle } from "@/lib/live-vehicles";
import { Empty } from "./kit";

/**
 * Latest positions drawn to scale (equirectangular, fitted to the vehicles shown). No map tiles:
 * the point is where vehicles are relative to each other, and which are moving.
 */
export function PositionMap({ vehicles, height = 420, highlight }: { vehicles: LiveVehicle[]; height?: number; highlight?: string }) {
  const router = useRouter();
  if (vehicles.length === 0) return <Empty>No positions yet.</Empty>;
  const W = 1000;
  const H = Math.round((height / 1000) * W * 1.6);
  const lats = vehicles.map((v) => v.latitude);
  const lons = vehicles.map((v) => v.longitude);
  const pad = 0.004;
  const minLat = Math.min(...lats) - pad;
  const maxLat = Math.max(...lats) + pad;
  const minLon = Math.min(...lons) - pad;
  const maxLon = Math.max(...lons) + pad;
  const x = (lon: number) => ((lon - minLon) / (maxLon - minLon)) * (W - 60) + 30;
  const y = (lat: number) => H - 30 - ((lat - minLat) / (maxLat - minLat)) * (H - 60);
  const grid = [0.2, 0.4, 0.6, 0.8];
  return (
    <div className="position-map" style={{ height }}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet" role="img" aria-label="Vehicle positions">
        {grid.map((f) => (
          <g key={f}>
            <line className="map-grid" x1={W * f} x2={W * f} y1={0} y2={H} />
            <line className="map-grid" x1={0} x2={W} y1={H * f} y2={H * f} />
          </g>
        ))}
        {vehicles.map((v) => (
          <g
            key={v.imei}
            className={`map-point${v.moving ? " moving" : ""}${highlight === v.imei ? " highlight" : ""}`}
            role="link"
            tabIndex={0}
            aria-label={`Inspect ${v.registrationNo || v.imei}`}
            onClick={() => router.push(`/live/inspector?imei=${v.imei}`)}
            onKeyDown={(e) => e.key === "Enter" && router.push(`/live/inspector?imei=${v.imei}`)}
          >
              <title>{`${v.registrationNo || v.imei}: ${formatNumber(v.speedKmph)} km/h, ${v.moving ? "moving" : "standing"}`}</title>
              {v.moving && <circle className="map-halo" cx={x(v.longitude)} cy={y(v.latitude)} r={14} />}
              <circle cx={x(v.longitude)} cy={y(v.latitude)} r={v.moving ? 6.5 : 5} />
          </g>
        ))}
      </svg>
      <div className="map-legend">
        <span>
          <i className="moving" /> Moving
        </span>
        <span>
          <i /> Standing
        </span>
        <span className="mono faint">
          {minLat.toFixed(3)}…{maxLat.toFixed(3)} N · {minLon.toFixed(3)}…{maxLon.toFixed(3)} E
        </span>
      </div>
    </div>
  );
}
