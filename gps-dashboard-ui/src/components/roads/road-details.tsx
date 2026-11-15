"use client";

import { useState } from "react";
import { KeyValues } from "@/components/ops/kit";
import { formatDateTime } from "@/lib/format";
import { directionLabel, yesNo, type RoadPieceDetails } from "@/lib/roads";

/** What Valhalla told us about one road piece (V13), with the raw edge it sent behind a toggle. */
export function RoadPieceDetailsView({ details }: { details: RoadPieceDetails | undefined }) {
  const [raw, setRaw] = useState(false);
  if (!details || details.wayId === undefined) return null;
  if (details.attributes == null) {
    return <p className="muted" style={{ marginTop: 10 }}>Learned before the details were kept: the next matching run adds them.</p>;
  }
  return (
    <div style={{ marginTop: 12 }}>
      <KeyValues
        rows={[
          ["Direction", directionLabel(details.traversability)],
          ["Use", details.use ?? "–"],
          ["Bridge / tunnel", `${yesNo(details.bridge)} / ${yesNo(details.tunnel)}`],
          ["Roundabout", yesNo(details.roundabout)],
          ["Toll road", yesNo(details.toll)],
          ["Curvature", details.curvature == null ? "–" : `${details.curvature} of 15${details.curvature >= 5 ? " (sharp)" : ""}`],
          ["Valhalla's speed", details.valhallaKmph == null ? "–" : `${details.valhallaKmph} km/h (${details.speedType ?? "?"})`],
          ["Truck route", yesNo(details.truckRoute)],
          ["Surface", details.surface ?? "–"],
          ["Heading", details.beginHeading == null ? "–" : `${details.beginHeading}° → ${details.endHeading}°`],
          ["Piece", `${details.lengthM} m · OSM way ${details.wayId}`],
          ["Seen", `${details.timesMatched}× since ${formatDateTime(details.firstSeenAt)}`],
        ]}
      />
      <button type="button" className="btn soft" style={{ marginTop: 10 }} onClick={() => setRaw(!raw)}>
        {raw ? "Hide" : "Show"} the raw Valhalla edge
      </button>
      {raw && (
        <pre className="mono" style={{ fontSize: 11.5, whiteSpace: "pre-wrap", wordBreak: "break-all", marginTop: 8, maxHeight: 260, overflow: "auto" }}>
          {JSON.stringify(JSON.parse(details.attributes), null, 2)}
        </pre>
      )}
    </div>
  );
}
