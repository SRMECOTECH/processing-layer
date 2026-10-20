"use client";

import { useId, useMemo, useRef, useState } from "react";
import { formatRate } from "@/lib/format";

const W = 1000;
const H = 240;

export type AreaSeries = { name: string; values: number[]; strong?: boolean };

/**
 * Two filled lines over the last minute (real per-second counts), averaged over `averageSeconds`
 * because the simulator sends each vehicle once per 2 s round. Hover reads any moment.
 */
export function AreaChart({ series: raw, averageSeconds = 6, unit = "msg/s" }: { series: AreaSeries[]; averageSeconds?: number; unit?: string }) {
  const id = useId().replace(/:/g, "");
  const ref = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);

  const series = useMemo(() => raw.map((s) => ({ ...s, values: trailingAverage(s.values, averageSeconds) })), [raw, averageSeconds]);
  const length = Math.max(0, ...series.map((s) => s.values.length));
  const max = niceCeiling(Math.max(1, ...series.flatMap((s) => s.values)) * 1.15);
  const x = (i: number) => (length <= 1 ? 0 : (i / (length - 1)) * W);
  const y = (v: number) => H - (v / max) * (H - 16);
  const index = hover ?? length - 1;

  return (
    <div className="area-chart">
      <div className="area-legend">
        {series.map((s) => (
          <span key={s.name}>
            <i className={s.strong ? "strong" : "soft"} />
            {s.name} <b className="mono">{formatRate(s.values[index] ?? 0)}</b> {unit}
          </span>
        ))}
        <span className="muted">{hover === null ? "now" : `${length - index} s ago`}</span>
      </div>
      <div
        className="area-plot"
        ref={ref}
        onPointerMove={(event) => {
          const box = ref.current?.getBoundingClientRect();
          if (!box || length === 0) return;
          const f = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
          setHover(Math.round(f * (length - 1)));
        }}
        onPointerLeave={() => setHover(null)}
      >
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label="Messages per second, last minute">
          <defs>
            {series.map((s, i) => (
              <linearGradient key={s.name} id={`${id}-g${i}`} x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="var(--accent)" stopOpacity={s.strong ? 0.22 : 0.12} />
                <stop offset="100%" stopColor="var(--accent)" stopOpacity={0} />
              </linearGradient>
            ))}
          </defs>
          {[0.25, 0.5, 0.75].map((f) => (
            <line key={f} className="grid-line" x1={0} x2={W} y1={H * f} y2={H * f} />
          ))}
          {series.map((s, i) => {
            if (s.values.length === 0) return null;
            const line = smooth(s.values.map((v, j) => [x(j), y(v)]));
            const area = `${line} L ${x(s.values.length - 1)} ${H} L 0 ${H} Z`;
            return (
              <g key={s.name}>
                <path d={area} fill={`url(#${id}-g${i})`} className="area-fill" style={{ d: `path("${area}")` }} />
                <path d={line} className={`area-line ${s.strong ? "strong" : "soft"}`} style={{ d: `path("${line}")` }} />
              </g>
            );
          })}
          {hover !== null && <line className="cursor-line" x1={x(hover)} x2={x(hover)} y1={0} y2={H} />}
        </svg>
      </div>
      <div className="area-axis mono">
        <span>T-{length}s</span>
        <span>T-{Math.round(length / 2)}s</span>
        <span className="accent-text">T-0s (current)</span>
      </div>
    </div>
  );
}

/**
 * Speedometer against calculated speed with a limit line and the flagged stretch shaded.
 * Fed with example numbers on the demo cards.
 */
export function CompareChart({
  primary,
  secondary,
  limit,
  flagFrom,
  flagTo,
  flagLabel,
  limitLabel,
  height = 200,
}: {
  primary: number[];
  secondary: number[];
  limit: number;
  flagFrom?: number;
  flagTo?: number;
  flagLabel?: string;
  limitLabel?: string;
  height?: number;
}) {
  const max = Math.max(limit, ...primary, ...secondary) * 1.2;
  const n = Math.max(primary.length, secondary.length);
  const x = (i: number) => (i / (n - 1)) * W;
  const y = (v: number) => H - (v / max) * H;
  const peak = primary.indexOf(Math.max(...primary));
  return (
    <div className="compare-chart" style={{ height }}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label="Speedometer against calculated speed">
        {flagFrom !== undefined && flagTo !== undefined && (
          <rect className="flag-band" x={x(flagFrom)} width={x(flagTo) - x(flagFrom)} y={0} height={H} />
        )}
        <line className="limit-line" x1={0} x2={W} y1={y(limit)} y2={y(limit)} />
        <path className="cmp-secondary" d={smooth(secondary.map((v, i) => [x(i), y(v)]))} />
        <path className="cmp-primary" d={smooth(primary.map((v, i) => [x(i), y(v)]))} />
      </svg>
      {limitLabel && (
        <span className="chart-note mono" style={{ top: `${(y(limit) / H) * 100}%` }}>
          {limitLabel}
        </span>
      )}
      {flagLabel && peak >= 0 && (
        <span className="chart-flag mono" style={{ left: `${(x(peak) / W) * 100}%` }}>
          {flagLabel}
        </span>
      )}
    </div>
  );
}

/** A thin ring showing a share (0..1). */
export function Ring({ value, size = 72 }: { value: number; size?: number }) {
  const r = 30;
  const c = 2 * Math.PI * r;
  return (
    <svg width={size} height={size} viewBox="0 0 72 72" className="ring" aria-hidden="true">
      <circle cx="36" cy="36" r={r} className="ring-track" />
      <circle
        cx="36"
        cy="36"
        r={r}
        className="ring-value"
        strokeDasharray={`${c * Math.max(0, Math.min(1, value))} ${c}`}
        transform="rotate(-90 36 36)"
      />
    </svg>
  );
}

function trailingAverage(values: number[], window: number): number[] {
  if (window <= 1 || values.length < window) return values;
  const out: number[] = [];
  let sum = 0;
  values.forEach((v, i) => {
    sum += v;
    if (i >= window) sum -= values[i - window];
    if (i >= window - 1) out.push(sum / window);
  });
  return out;
}

function niceCeiling(value: number): number {
  const magnitude = Math.pow(10, Math.floor(Math.log10(value)));
  const step = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].find((s) => s * magnitude >= value) ?? 10;
  return step * magnitude;
}

function smooth(points: number[][]): string {
  if (points.length === 0) return "";
  let d = `M ${points[0][0].toFixed(1)} ${points[0][1].toFixed(1)}`;
  for (let i = 1; i < points.length; i++) {
    const [x0, y0] = points[i - 1];
    const [x1, y1] = points[i];
    const m = (x0 + x1) / 2;
    d += ` C ${m.toFixed(1)} ${y0.toFixed(1)}, ${m.toFixed(1)} ${y1.toFixed(1)}, ${x1.toFixed(1)} ${y1.toFixed(1)}`;
  }
  return d;
}
