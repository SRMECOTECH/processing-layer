"use client";

import { Activity, Sigma } from "lucide-react";
import { CompareChart } from "@/components/ops/charts";
import { Card, KeyValues, PageTitle, Tag, Tile } from "@/components/ops/kit";

// Example: raw GPS distance increments jitter; the filtered line follows the true path.
const RAW = [12, 19, 9, 22, 14, 25, 11, 27, 18, 24, 13, 26, 17, 21, 15];
const FILTERED = [14, 16, 15, 17, 17, 18, 18, 19, 19, 20, 19, 20, 20, 20, 19];

export default function KalmanPage() {
  return (
    <div className="stack">
      <PageTitle
        title="Kalman filter distance"
        subtitle="Cumulative trip distance from smoothed positions. Raw GPS jitters around the true path, which inflates distance; the filter removes the jitter before each increment is added."
      />

      <Card icon={Activity} title="Raw against filtered distance increments" subtitle="Metres added per reading on one trip" live={false} badges={<Tag mono>Q=0.02 · R=4.0</Tag>}>
        <div className="legend-row">
          <span>
            <i />
            Raw GPS increment
          </span>
          <span>
            <i className="dashed" />
            Kalman filtered
          </span>
        </div>
        <CompareChart primary={RAW} secondary={FILTERED} limit={30} height={220} />
        <div className="grid-3" style={{ marginTop: 16 }}>
          <Tile label="Noise removed" value="-38.2" unit="%" tone="accent" />
          <Tile label="Positional accuracy" value="±1.2" unit="m" />
          <Tile label="Fit against ground truth" value="0.994" unit="R²" />
        </div>
      </Card>

      <Card icon={Sigma} title="How the distance is built" subtitle="Applied on every trip increment." live={false}>
        <KeyValues
          rows={[
            ["Input", "Latitude, longitude, reported speed, time gap"],
            ["Prediction", "Constant velocity model between pings"],
            ["Update", "Weighted by GPS accuracy and satellite count"],
            ["Output", "Smoothed position, distance increment added to the trip"],
            ["Reset", "On trip start, or a gap longer than 5 minutes"],
          ]}
        />
      </Card>
    </div>
  );
}
