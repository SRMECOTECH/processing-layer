"use client";

import { useMemo, useRef, useState } from "react";
import { formatRate } from "@/lib/format";

export type Series = {
  name: string;
  /** Oldest first, one value per second. */
  values: number[];
  color: string;
};

type Props = {
  series: Series[];
  unit: string;
  /**
   * Plot the average of the last N seconds instead of each raw second. The simulator sends every vehicle
   * once per 2 s round, so raw seconds alternate between full and empty; an even window evens that out.
   */
  averageSeconds?: number;
};

const WIDTH = 1000;
const HEIGHT = 200;
const TOP_PADDING = 12;

/** A quiet line chart of the last minute: thin lines, one hairline baseline, a value readout on hover. */
export function LineChart({ series: rawSeries, unit, averageSeconds = 1 }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);

  const series = useMemo(
    () => rawSeries.map((s) => ({ ...s, values: trailingAverage(s.values, averageSeconds) })),
    [rawSeries, averageSeconds],
  );

  const length = Math.max(0, ...series.map((s) => s.values.length));
  const max = useMemo(() => {
    const highest = Math.max(1, ...series.flatMap((s) => s.values));
    return niceCeiling(highest * 1.1);
  }, [series]);

  const x = (index: number) => (length <= 1 ? 0 : (index / (length - 1)) * WIDTH);
  const y = (value: number) => HEIGHT - (value / max) * (HEIGHT - TOP_PADDING);

  const paths = series.map((s) => (s.values.length === 0 ? "" : smoothPath(s.values.map((v, i) => [x(i), y(v)]))));

  function onMove(event: React.PointerEvent<HTMLDivElement>) {
    const box = ref.current?.getBoundingClientRect();
    if (!box || length === 0) return;
    const fraction = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    setHoverIndex(Math.round(fraction * (length - 1)));
  }

  const readIndex = hoverIndex ?? length - 1;
  const secondsAgo = length - readIndex;

  return (
    <div>
      <div className="chart-legend" style={{ marginBottom: 16 }}>
        {series.map((s) => (
          <span key={s.name}>
            <span className="legend-swatch" style={{ background: s.color }} />
            {s.name} <strong>{formatRate(s.values[readIndex] ?? 0)}</strong> {unit}
          </span>
        ))}
        <span className="faint">{hoverIndex === null ? "latest" : `${secondsAgo} s ago`}</span>
      </div>
      <div
        className="chart"
        ref={ref}
        onPointerMove={onMove}
        onPointerLeave={() => setHoverIndex(null)}
      >
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" role="img" aria-label={`Messages per second over the last minute`}>
          <line className="baseline" x1={0} x2={WIDTH} y1={HEIGHT} y2={HEIGHT} />
          {paths.map((d, i) => (
            <path key={series[i].name} d={d} style={{ stroke: series[i].color, d: d ? `path("${d}")` : undefined }} />
          ))}
          {hoverIndex !== null && (
            <line className="cursor" x1={x(hoverIndex)} x2={x(hoverIndex)} y1={0} y2={HEIGHT} />
          )}
        </svg>
      </div>
      <div className="chart-axis">
        <span>{length > 0 ? `${length} s ago` : ""}</span>
        <span>peak scale {formatRate(max)} {unit}</span>
        <span>now</span>
      </div>
    </div>
  );
}

/**
 * Each point becomes the average of itself and the window-1 seconds before it. The first window-1 seconds
 * have no full window behind them and are left out, so the line does not wobble at its start.
 */
function trailingAverage(values: number[], window: number): number[] {
  if (window <= 1 || values.length < window) return values;
  const averages: number[] = [];
  let sum = 0;
  values.forEach((value, index) => {
    sum += value;
    if (index >= window) sum -= values[index - window];
    if (index >= window - 1) averages.push(sum / window);
  });
  return averages;
}

function niceCeiling(value: number): number {
  const magnitude = Math.pow(10, Math.floor(Math.log10(value)));
  const steps = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
  const step = steps.find((s) => s * magnitude >= value) ?? 10;
  return step * magnitude;
}

/** Monotone-ish smoothing: gentle curves without overshooting below zero. */
function smoothPath(points: [number, number][]): string {
  if (points.length === 1) return `M ${points[0][0]} ${points[0][1]}`;
  let d = `M ${points[0][0].toFixed(1)} ${points[0][1].toFixed(1)}`;
  for (let i = 1; i < points.length; i++) {
    const [x0, y0] = points[i - 1];
    const [x1, y1] = points[i];
    const mid = (x0 + x1) / 2;
    d += ` C ${mid.toFixed(1)} ${y0.toFixed(1)}, ${mid.toFixed(1)} ${y1.toFixed(1)}, ${x1.toFixed(1)} ${y1.toFixed(1)}`;
  }
  return d;
}
