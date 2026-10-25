"use client";

import { ChartColumn, Truck } from "lucide-react";
import { Card, KeyValues, PageTitle, Progress, Tag } from "@/components/ops/kit";
import { formatDistance, formatDuration, formatNumber, formatPercent } from "@/lib/format";
import { useLiveVehicles } from "@/lib/live-vehicles";
import type { TripCounts, TripView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function TripEfficiencyPage() {
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const trips = usePoll<TripView[]>("/api/trips?limit=500", 15000);
  const { vehicles } = useLiveVehicles(40, "", 5000);

  const c = counts.data;
  const total = c ? c.openTrips + c.closedTrips : 0;
  const completion = total ? c!.closedTrips / total : 0;
  const moving = vehicles ? vehicles.filter((v) => v.moving).length / Math.max(1, vehicles.length) : 0;
  const closed = (trips.data ?? []).filter((t) => t.status === "CLOSED");
  const medianDuration = median(closed.map((t) => t.durationSeconds));
  const medianDistance = median(closed.map((t) => t.distanceMeters));

  return (
    <div className="stack">
      <PageTitle
        title="Vehicle state and trip efficiency"
        subtitle="Trip lifecycle completion and how the fleet splits between driving and standing. Medians are used because one replayed drive can add an unrealistic trip."
      />

      <div className="grid-2">
        <Card icon={Truck} title="Vehicle state and trip efficiency" subtitle="Trip lifecycle completion and state transitions" live actions={<ChartColumn size={18} className="accent-text" />}>
          <div style={{ display: "grid", gap: 18 }}>
            <div>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <strong>Trip completion rate</strong>
                <span className="mono accent-text">{formatPercent(completion)}</span>
              </div>
              <Progress value={completion} />
              <div className="muted" style={{ display: "flex", justifyContent: "space-between", fontSize: 12.5, marginTop: 6 }}>
                <span>Completed: {formatNumber(c?.closedTrips)} trips</span>
                <span>In transit: {formatNumber(c?.openTrips)}</span>
              </div>
            </div>
            <div>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <strong>Driving against standing</strong>
                <span className="mono accent-text">{formatPercent(moving)} driving</span>
              </div>
              <Progress value={moving} />
              <div className="muted" style={{ display: "flex", justifyContent: "space-between", fontSize: 12.5, marginTop: 6 }}>
                <span>Sample of {formatNumber(vehicles?.length)} vehicles</span>
                <span>Standing now: {formatNumber(c?.openStops)}</span>
              </div>
            </div>
            <div>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <strong>Route deviation tolerance</strong>
                <span className="mono">Avg 2.4 min</span>
              </div>
              <Progress value={0.15} tone="warn" />
              <div className="muted" style={{ display: "flex", justifyContent: "space-between", fontSize: 12.5, marginTop: 6 }}>
                <span>Corridor buffer: 250 m</span>
                <span>
                  Threshold: &lt; 5 min <Tag tone="warn">DEMO</Tag>
                </span>
              </div>
            </div>
          </div>
        </Card>

        <Card icon={ChartColumn} title="Typical trip" subtitle="Completed trips in the last 24 hours." live>
          <KeyValues
            rows={[
              ["Typical duration", closed.length ? formatDuration(medianDuration) : "–"],
              ["Typical distance", closed.length ? formatDistance(medianDistance) : "–"],
              ["Trips completed", formatNumber(c?.closedTrips)],
              ["Stops recorded", formatNumber(c?.closedStops)],
              ["Highest reported speed", trips.data?.length ? `${formatNumber(Math.max(...trips.data.map((t) => t.maxSpeedKmph)))} km/h` : "–"],
            ]}
          />
        </Card>
      </div>
    </div>
  );
}

function median(values: number[]): number {
  if (values.length === 0) return 0;
  const s = [...values].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}
