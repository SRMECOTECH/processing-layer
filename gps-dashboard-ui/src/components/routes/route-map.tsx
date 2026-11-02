"use client";

import type { RouteEvent, TrailPoint, TripStop } from "@/lib/routes";
import styles from "./routes.module.css";

const WIDTH = 1000;
const MAX_HEIGHT = 640;

type Props = {
  line: [number, number][];
  firstLine?: [number, number][];
  trail?: TrailPoint[];
  stops?: TripStop[];
  events?: RouteEvent[];
  origin?: { lat: number; lon: number; name: string | null };
  destination?: { lat: number; lon: number; name: string | null };
  truckTone?: "good" | "bad" | "warn" | "neutral" | "accent" | "ink";
  label?: string;
};

/**
 * The trip on a plain SVG map (no tiles, no map library): the planned road in grey, the original plan dotted after a
 * re-plan, the truck's real trail green on the route and red off it, the stops, deviation pins and the truck itself.
 * Equirectangular projection of the trip's box, x scaled by cos(latitude), so shapes keep their proportions.
 */
export function RouteMap({ line, firstLine, trail = [], stops = [], events = [], origin, destination, truckTone = "accent", label }: Props) {
  // the box is the plan (and its stops); trail points add to it only when near it, so a reading from somewhere else
  // (a device that was in another truck before) cannot shrink the route to a dot
  const planned: [number, number][] = [...line, ...(firstLine ?? [])];
  stops.forEach((s) => planned.push([s.latitude, s.longitude]));
  if (planned.length < 2) return null;
  let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
  for (const [la, lo] of planned) {
    minLat = Math.min(minLat, la);
    maxLat = Math.max(maxLat, la);
    minLon = Math.min(minLon, lo);
    maxLon = Math.max(maxLon, lo);
  }
  const marginLat = Math.max((maxLat - minLat) * 0.3, 0.05);
  const marginLon = Math.max((maxLon - minLon) * 0.3, 0.05);
  const near = (la: number, lo: number) => la >= minLat - marginLat && la <= maxLat + marginLat && lo >= minLon - marginLon && lo <= maxLon + marginLon;
  for (const p of trail) {
    if (near(p[0], p[1])) {
      minLat = Math.min(minLat, p[0]);
      maxLat = Math.max(maxLat, p[0]);
      minLon = Math.min(minLon, p[1]);
      maxLon = Math.max(maxLon, p[1]);
    }
  }
  const k = Math.cos(((minLat + maxLat) / 2) * (Math.PI / 180));
  const spanX = Math.max((maxLon - minLon) * k, 0.005);
  const spanY = Math.max(maxLat - minLat, 0.005);
  const pad = 0.07;
  // fit the trip's box in WIDTH x MAX_HEIGHT, keeping its proportions
  const scale = Math.min(WIDTH / (spanX * (1 + 2 * pad)), MAX_HEIGHT / (spanY * (1 + 2 * pad)));
  const height = Math.max(spanY * (1 + 2 * pad) * scale, 260);
  const offX = (WIDTH - spanX * scale) / 2;
  const offY = (height - spanY * scale) / 2;
  const x = (lon: number) => offX + (lon - minLon) * k * scale;
  const y = (lat: number) => offY + (maxLat - lat) * scale;
  const path = (pts: [number, number][]) => pts.map(([la, lo], i) => `${i ? "L" : "M"}${x(lo).toFixed(1)} ${y(la).toFixed(1)}`).join(" ");

  // the trail in runs of on / off the line; a gap over 5 km between two readings (a jump) is not drawn as a road
  const runs: { off: boolean; pts: [number, number][] }[] = [];
  let previous: [number, number] | null = null;
  for (const p of trail) {
    const last = runs[runs.length - 1];
    const point: [number, number] = [p[0], p[1]];
    const jump = previous !== null && Math.hypot(point[0] - previous[0], (point[1] - previous[1]) * k) * 111.32 > 5;
    if (!last || jump) {
      runs.push({ off: p[4], pts: [point] });
    } else if (last.off !== p[4]) {
      runs.push({ off: p[4], pts: [last.pts[last.pts.length - 1], point] });
    } else {
      last.pts.push(point);
    }
    previous = point;
  }
  const truck = trail.length ? trail[trail.length - 1] : null;
  const before = trail.length > 1 ? trail[trail.length - 2] : null;
  const heading = truck && before ? (Math.atan2(-(truck[0] - before[0]), (truck[1] - before[1]) * k) * 180) / Math.PI : 0;

  // a scale bar of a round length about a fifth of the width
  const kmPerPx = 111.32 / scale;
  const target = kmPerPx * 200;
  const nice = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500].reduce((a, b) => (Math.abs(b - target) < Math.abs(a - target) ? b : a));
  const barPx = nice / kmPerPx;

  const toneVar = (t: string) => (t === "neutral" ? "var(--line-strong)" : t === "ink" ? "var(--text)" : `var(--${t})`);
  const stopFill = (s: TripStop) =>
    s.status === "MISSED" ? "var(--bad)" : s.status === "DEPARTED" || s.status === "PASSED" ? "var(--good)" : s.status === "PENDING" ? "var(--line-strong)" : "var(--accent)";
  const pins = events.filter((e) => e.type === "ROUTE_DEVIATION" || e.type === "WRONG_DIRECTION");

  return (
    <div>
      <svg className={styles.map} viewBox={`0 0 ${WIDTH} ${height.toFixed(0)}`} role="img" aria-label={label ?? "Planned route and the truck's trail"}>
        {firstLine && firstLine.length > 1 && <path className={styles.firstPlan} d={path(firstLine)} />}
        <path className={styles.planned} d={path(line)} />
        {runs.map((r, i) => r.pts.length > 1 && <path key={i} className={r.off ? styles.off : styles.on} d={path(r.pts)} />)}
        {stops.map((s) => (
          <g key={s.seq}>
            <circle className={styles.mapStop} cx={x(s.longitude)} cy={y(s.latitude)} r={s.kind === "MANUAL" ? 7 : 5} fill={stopFill(s)}>
              <title>{`${s.name} · ${s.status}`}</title>
            </circle>
            {s.kind === "MANUAL" && (
              <text className={styles.mapLabel} x={x(s.longitude) + 10} y={y(s.latitude) + 4}>
                {s.name}
              </text>
            )}
          </g>
        ))}
        {pins.map((e, i) => (
          <rect
            key={i}
            className={styles.pin}
            x={x(e.longitude) - 6}
            y={y(e.latitude) - 6}
            width={12}
            height={12}
            transform={`rotate(45 ${x(e.longitude)} ${y(e.latitude)})`}
            fill={e.type === "WRONG_DIRECTION" ? "var(--warn)" : "var(--bad)"}
          >
            <title>{`${e.type === "WRONG_DIRECTION" ? "Wrong direction" : "Off route"} from ${new Date(e.startedAt).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}`}</title>
          </rect>
        ))}
        {origin && (
          <g>
            <circle cx={x(origin.lon)} cy={y(origin.lat)} r={9} fill="var(--text)" stroke="var(--surface)" strokeWidth={3} />
            <text className={styles.mapLabel} x={x(origin.lon) + 13} y={y(origin.lat) + 4}>
              {origin.name ?? "Start"}
            </text>
          </g>
        )}
        {destination && (
          <g>
            <circle cx={x(destination.lon)} cy={y(destination.lat)} r={11} fill="var(--surface)" stroke="var(--text)" strokeWidth={4} />
            <circle cx={x(destination.lon)} cy={y(destination.lat)} r={4} fill="var(--text)" />
            <text className={styles.mapLabel} x={x(destination.lon) + 15} y={y(destination.lat) + 4}>
              {destination.name ?? "Destination"}
            </text>
          </g>
        )}
        {truck && (
          <g transform={`translate(${x(truck[1])} ${y(truck[0])}) rotate(${heading})`}>
            <circle r={11} fill={toneVar(truckTone)} stroke="var(--surface)" strokeWidth={3} />
            <path d="M -4 -4 L 6 0 L -4 4 Z" fill="var(--surface)" />
          </g>
        )}
        <g transform={`translate(20 ${height - 20})`}>
          <rect width={barPx} height={4} fill="var(--text)" opacity={0.6} />
          <text className={styles.mapLabel} y={-6}>
            {nice >= 1 ? `${nice} km` : `${nice * 1000} m`}
          </text>
        </g>
      </svg>
      <div className={styles.legend}>
        <span>
          <i style={{ background: "var(--text)", opacity: 0.22 }} />
          planned road
        </span>
        {firstLine && (
          <span>
            <i style={{ background: "repeating-linear-gradient(90deg, var(--text) 0 4px, transparent 4px 8px)", opacity: 0.4 }} />
            first plan
          </span>
        )}
        <span>
          <i style={{ background: "var(--good)" }} />
          driven on route
        </span>
        <span>
          <i style={{ background: "var(--bad)" }} />
          driven off route
        </span>
      </div>
    </div>
  );
}
