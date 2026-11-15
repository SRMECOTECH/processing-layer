"use client";

import { Empty, Tag } from "@/components/ops/kit";
import { formatTime } from "@/lib/format";
import { SOURCE_LABEL, classLabel, type RoadPass } from "@/lib/roads";

/**
 * Road passes, newest first: each time the vehicle drove a road, with how it drove there against the road's limit and
 * the speed Valhalla expects on it.
 */
export function RoadPassesTable({ passes, empty }: { passes: RoadPass[] | undefined; empty: string }) {
  if (!passes || passes.length === 0) return <Empty>{empty}</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Road</th>
            <th>Entered → left</th>
            <th>Distance</th>
            <th>Driving / standing</th>
            <th>Speed: avg · max</th>
            <th>Valhalla expects</th>
            <th>Limit</th>
            <th>Above limit</th>
            <th>Road</th>
          </tr>
        </thead>
        <tbody>
          {passes.map((p) => (
            <tr key={p.id}>
              <td>
                <strong>{p.label}</strong>
                <div className="muted">
                  {p.roadClass ? classLabel(p.roadClass) : "not on a known road"}
                  {p.area === "TOWN" && " · town"}
                </div>
              </td>
              <td className="mono">
                {formatTime(p.enteredAt)} → {formatTime(p.leftAt)}
                <div className="muted">{p.readings} readings</div>
              </td>
              <td className="mono">{p.km} km</td>
              <td className="mono">
                {p.movingMin} / {p.stoppedMin} min
              </td>
              <td className="mono">
                {p.avgKmph} · {p.maxKmph} km/h
              </td>
              <td className="mono">
                {p.expectedKmph != null ? `${p.expectedKmph} km/h` : "–"}
                {p.expectedKmph != null && p.avgKmph > 0 && (
                  <div className="muted">truck at {Math.round((p.avgKmph / p.expectedKmph) * 100)}%</div>
                )}
              </td>
              <td>
                {p.limitKmph} km/h
                <div className="muted">{SOURCE_LABEL[p.limitSource] ?? p.limitSource}</div>
              </td>
              <td className="mono">
                {p.overReadings > 0 ? (
                  <>
                    <Tag tone="bad">{p.overMin} min</Tag>
                    <div className="muted">up to +{p.maxExcessKmph}</div>
                  </>
                ) : (
                  "–"
                )}
              </td>
              <td className="muted">
                {p.pieces} pieces
                {p.bridges > 0 && <div>{p.bridges} bridge{p.bridges > 1 ? "s" : ""}</div>}
                {p.maxCurvature != null && p.maxCurvature >= 5 && <div>sharp bends ({p.maxCurvature})</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
