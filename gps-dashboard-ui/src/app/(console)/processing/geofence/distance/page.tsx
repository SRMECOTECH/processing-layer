"use client";

import { Ruler, Sigma } from "lucide-react";
import { Card } from "@/components/ops/kit";
import { ModuleHeader, PlannedNote } from "@/components/ops/module";
import { formatDistance } from "@/lib/format";
import { NESTED_DISTANCES } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const GEOFENCE = moduleById("geofence")!;
const LEVELS = ["Region", "Site", "Yard", "Dock"];

export default function NestedDistancePage() {
  return (
    <div className="stack">
      <ModuleHeader
        module={GEOFENCE}
        title="Nested distance"
        description="For each vehicle, the signed distance to the boundary of every zone level around it: negative means inside and how deep, positive means outside and how far."
      />
      <PlannedNote what="Nested distance" />

      <Card icon={Ruler} title="Distance to each level" subtitle="Metres to the nearest boundary." live={false} flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Vehicle</th>
                <th>Deepest zone</th>
                {LEVELS.map((l) => (
                  <th key={l} className="right">
                    {l}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {NESTED_DISTANCES.map((row) => (
                <tr key={row.vehicle}>
                  <td className="mono">{row.vehicle}</td>
                  <td className="muted">{row.deepest}</td>
                  {LEVELS.map((l) => {
                    const d = row.levels.find(([level]) => level === l)?.[1];
                    return (
                      <td key={l} className="right">
                        {d === undefined ? (
                          <span className="faint">–</span>
                        ) : (
                          <span className={`signed mono ${d < 0 ? "inside" : "outside"}`}>
                            {d < 0 ? "−" : "+"}
                            {formatDistance(Math.abs(d))}
                          </span>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card icon={Sigma} title="How the distance is measured">
        <div className="grid-2">
          <pre className="code">{`for level in path(root → deepest):
  d = distance(point, boundary(level))
  signed = inside(point, level) ? −d : +d

nearest edge: point-to-segment on
local metres (equirectangular around
the zone's centroid), haversine for
distances over 50 km`}</pre>
          <ul className="plain-list">
            <li>Inside a dock 8 m from its edge reads −8 m at the dock level, and −812 m at the site level.</li>
            <li>A vehicle on the boundary (within the 15 m tolerance) keeps its previous zone until it clearly crosses.</li>
            <li>Distances are kept per vehicle in Redis so the next reading only checks nearby boundaries.</li>
          </ul>
        </div>
      </Card>
    </div>
  );
}
