"use client";

import Link from "next/link";
import { Activity, Sigma } from "@/components/icons";
import { Card, Empty, KeyValues, Stats, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import { formatDistance, formatNumber, formatPercent } from "@/lib/format";
import { usePoll } from "@/lib/use-poll";

type VehicleRow = Record<string, string>;
const DISTANCE = moduleById("distance")!;

export default function KalmanPage() {
  const states = usePoll<VehicleRow[]>("/api/vehicles/states?limit=500", 5000);
  const rows = (states.data ?? [])
    .map((v) => ({
      imei: v.imei,
      name: v.vehicleRegistrationNo || v.imei,
      raw: Number(v.totalDistanceMeters || 0),
      kalman: Number(v.kalmanDistanceMeters || 0),
      readings: Number(v.readings || 0),
      jitter: Number(v.jitterFilteredReadings || 0),
      jumps: Number(v.jumpFilteredReadings || 0),
      odometer: Number(v.odometerReadings || 0),
    }))
    .sort((a, b) => b.raw - a.raw);
  const totalRaw = rows.reduce((s, r) => s + r.raw, 0);
  const totalKalman = rows.reduce((s, r) => s + r.kalman, 0);
  const jitter = rows.reduce((s, r) => s + r.jitter, 0);
  const readings = rows.reduce((s, r) => s + r.readings, 0);

  return (
    <div className="stack">
      <ModuleHeader
        module={DISTANCE}
        title="Cumulative distance · Kalman vs raw"
        description="Two ways to add up each vehicle's distance, side by side: the Traccar rules (odometer first, otherwise haversine, parking jitter clamped, impossible jumps ignored) and a Kalman-smoothed track whose length only grows while the vehicle moves."
      />

      <Stats>
        <Tile label="Fleet distance, Traccar rules" value={formatDistance(totalRaw)} big />
        <Tile label="Fleet distance, Kalman track" value={formatDistance(totalKalman)} big foot={totalRaw ? `${formatPercent(totalKalman / totalRaw - 1)} against Traccar` : undefined} />
        <Tile label="Parking jitter removed" value={jitter} big foot={readings ? `${formatPercent(jitter / readings)} of readings clamped` : undefined} />
        <Tile label="Readings" value={readings} big />
      </Stats>

      <Card icon={Activity} title="Per vehicle" subtitle="From Redis gps:state:vehicle:* (vehicle-state consumer). Click a vehicle to inspect its track." live flush>
        {rows.length === 0 ? (
          <Empty>{states.data ? "No vehicle state yet." : "Waiting for the service."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Vehicle</th>
                  <th className="right">Traccar rules</th>
                  <th className="right">Kalman track</th>
                  <th className="right">Difference</th>
                  <th className="right">Readings</th>
                  <th className="right">From odometer</th>
                  <th className="right">Jitter clamped</th>
                  <th className="right">Jumps ignored</th>
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, 40).map((r) => (
                  <tr key={r.imei}>
                    <td>
                      <Link className="link mono" href={`/live/inspector?imei=${r.imei}`}>
                        {r.name}
                      </Link>
                    </td>
                    <td className="right mono">{formatDistance(r.raw)}</td>
                    <td className="right mono">{formatDistance(r.kalman)}</td>
                    <td className="right mono muted">{r.raw > 0 ? formatPercent(r.kalman / r.raw - 1) : "–"}</td>
                    <td className="right mono">{formatNumber(r.readings)}</td>
                    <td className="right mono">{formatNumber(r.odometer)}</td>
                    <td className="right mono">{formatNumber(r.jitter)}</td>
                    <td className={`right mono${r.jumps ? " warn" : ""}`}>{formatNumber(r.jumps)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card icon={Sigma} title="How the two distances are built" subtitle="Both run on every reading.">
        <div className="grid-2">
          <KeyValues
            rows={[
              ["Traccar rules", "CalculateMovementStep (processing)"],
              ["1", "Device odometer delta, when it reports one and it is believable"],
              ["2", "Otherwise haversine on the WGS-84 equatorial radius"],
              ["3", "Under 10 m while standing: clamped to the last position, 0 m"],
              ["4", "Needs over 200 km/h and over 500 m: ignored as a glitch"],
            ]}
          />
          <KeyValues
            rows={[
              ["Kalman track", "VehicleStateProcessor (vehicle-state consumer)"],
              ["Model", "Constant velocity, local metres east/north"],
              ["Noise", "GPS 12 m, acceleration 1.5 m/s²"],
              ["Outliers", "Mahalanobis gate (99.9%), reset after 3 in a row"],
              ["Distance", "Grows only while the vehicle is moving"],
            ]}
          />
        </div>
      </Card>

      <HowItWorks module={DISTANCE} />
    </div>
  );
}
