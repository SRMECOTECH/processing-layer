"use client";

import { useState } from "react";
import { Empty, Facts, PageHead, Section, ServiceNotice } from "@/components/ui";
import {
  formatCoordinate,
  formatDateTime,
  formatDistance,
  formatDuration,
  formatNumber,
  formatTime,
  vehicleName,
} from "@/lib/format";
import type { GpsPositionView, StopView, TripView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

type Filter = "ALL" | "OPEN" | "CLOSED";
const ROWS = 25;

export default function RulesPage() {
  const trips = usePoll<TripView[]>("/api/trips?limit=500", 5000);
  const stops = usePoll<StopView[]>("/api/stops?limit=500", 5000);
  const readings = usePoll<GpsPositionView[]>("/api/gps-positions/recently-stored", 2000);

  const error = trips.error ?? readings.error;
  const hasData = Boolean(trips.data || readings.data);

  return (
    <>
      <ServiceNotice error={error} hasData={hasData} />
      <PageHead
        title="Rules and trips"
        lead="What the business logic makes of the raw positions: trips from ignition to stop, where vehicles stood still, and every reading as it was stored."
      />

      <Trips trips={trips.data} />
      <Stops stops={stops.data} />

      <Section
        title="Latest readings"
        description="The last positions written to PostgreSQL, after validation and movement calculation. Distance and gap are measured from the vehicle's previous reading."
        meta="Refreshes every 2 seconds"
      >
        {!readings.data ? (
          <Empty>Waiting for the service.</Empty>
        ) : readings.data.length === 0 ? (
          <Empty>No positions stored yet. Start the fleet to see readings arrive.</Empty>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Recorded</th>
                  <th>Vehicle</th>
                  <th className="right">Speed</th>
                  <th>Movement</th>
                  <th className="right">Distance</th>
                  <th className="right">Gap</th>
                  <th>Position</th>
                </tr>
              </thead>
              <tbody>
                {readings.data.map((r) => (
                  <tr key={`${r.imei}-${r.recordedAt}`}>
                    <td className="muted">{formatTime(r.recordedAt)}</td>
                    <td>{vehicleName(r.vehicleRegistrationNo, r.imei)}</td>
                    <td className="right">{formatNumber(r.speedKmph)} km/h</td>
                    <td className={r.movementStatus === "MOVING" ? "" : "muted"}>
                      {r.movementStatus === "MOVING" ? "Moving" : "Stopped"}
                    </td>
                    <td className="right">{formatDistance(r.distanceFromPreviousMeters)}</td>
                    <td className="right">{formatDuration(r.secondsSincePreviousReading)}</td>
                    <td className="mono muted">{formatCoordinate(r.latitude, r.longitude)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Section
        title="Over-speed dual verification"
        description="A speed alert only counts when the device speedometer and the speed worked out from distance over time agree. Disagreement points to a tampered or faulty sensor."
        demo
      >
        <div className="split">
          <Facts
            rows={[
              ["Fleet profile", "Logistics Alpha, hazardous material"],
              ["Speed ceiling", "60 km/h"],
              ["Must last", "60 seconds"],
              ["50 to 60 km/h", "Log an event"],
              ["Above 60 km/h on both checks", "Dispatch automatically"],
            ]}
          />
          <Facts
            rows={[
              ["Speedometer", "74.2 km/h"],
              ["Calculated from positions", "54.8 km/h"],
              ["Difference", <span className="bad" key="d">+19.4 km/h</span>],
              ["Verdict", <span className="bad" key="v">Possible tampering</span>],
            ]}
          />
        </div>
      </Section>

      <div className="columns">
        <Section title="Corridor guard" description="Alerts when a vehicle leaves its planned route by more than the buffer." demo>
          <Facts
            rows={[
              ["Buffer around route", "50 m"],
              ["Vehicles off corridor", <span className="bad" key="b">1, 180 m away</span>],
              ["Unplanned detour", "14 min 20 s off route"],
              ["Rejoins route", "In 1.4 km, exit 42B"],
            ]}
          />
        </Section>
        <Section title="Dispatch and escalations" description="Notifications sent when a rule fires." demo>
          <ul className="list">
            <li>
              <div className="list-title">
                <span>Operations desk webhook</span>
                <span className="faint">just now</span>
              </div>
              <p className="list-text">Over-speed held for more than 60 s by vehicle 108. Delivered in 42 ms.</p>
            </li>
            <li>
              <div className="list-title">
                <span>Driver in-cab notification</span>
                <span className="faint">2 min ago</span>
              </div>
              <p className="list-text">Corridor departure warning sounded. Device acknowledged.</p>
            </li>
            <li>
              <div className="list-title">
                <span>Incident ledger</span>
                <span className="faint">8 min ago</span>
              </div>
              <p className="list-text">Audit record created in PostgreSQL.</p>
            </li>
          </ul>
        </Section>
      </div>
    </>
  );
}

function Trips({ trips }: { trips: TripView[] | undefined }) {
  const [filter, setFilter] = useState<Filter>("ALL");
  const list = (trips ?? []).filter((t) => filter === "ALL" || t.status === filter);

  return (
    <Section
      title="Trips"
      description="A trip starts when a vehicle begins to move and ends when it has been standing still long enough. Last 24 hours, newest first."
      meta={trips ? `${formatNumber(list.length)} shown of ${formatNumber(trips.length)}` : undefined}
    >
      <div className="toolbar">
        <div className="choices" role="group" aria-label="Filter trips">
          {(
            [
              ["ALL", "All"],
              ["OPEN", "In progress"],
              ["CLOSED", "Completed"],
            ] as [Filter, string][]
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              className="text-button"
              aria-pressed={filter === value}
              onClick={() => setFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      {!trips ? (
        <Empty>Waiting for the service.</Empty>
      ) : list.length === 0 ? (
        <Empty>No trips here yet. They appear once vehicles have been driving for a while.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Vehicle</th>
                <th>Status</th>
                <th>Started</th>
                <th className="right">Duration</th>
                <th className="right">Distance</th>
                <th className="right">Top speed</th>
                <th className="right">Readings</th>
              </tr>
            </thead>
            <tbody>
              {list.slice(0, ROWS).map((t) => (
                <tr key={`${t.imei}-${t.startRecordedAt}`}>
                  <td>{vehicleName(t.vehicleRegistrationNo, t.imei)}</td>
                  <td className={t.status === "OPEN" ? "good" : "muted"}>{t.status === "OPEN" ? "In progress" : "Completed"}</td>
                  <td className="muted">{formatDateTime(t.startRecordedAt)}</td>
                  <td className="right">{formatDuration(t.durationSeconds)}</td>
                  <td className="right">{formatDistance(t.distanceMeters)}</td>
                  <td className="right">{formatNumber(t.maxSpeedKmph)} km/h</td>
                  <td className="right">{formatNumber(t.readingCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}

function Stops({ stops }: { stops: StopView[] | undefined }) {
  return (
    <Section
      title="Stops"
      description="Places where a vehicle stood still between trips. Last 24 hours, newest first."
      meta={stops ? `${formatNumber(Math.min(stops.length, ROWS))} shown of ${formatNumber(stops.length)}` : undefined}
    >
      {!stops ? (
        <Empty>Waiting for the service.</Empty>
      ) : stops.length === 0 ? (
        <Empty>No stops recorded yet.</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Vehicle</th>
                <th>Status</th>
                <th>Started</th>
                <th className="right">Duration</th>
                <th>Location</th>
                <th className="right">Readings</th>
              </tr>
            </thead>
            <tbody>
              {stops.slice(0, ROWS).map((s) => (
                <tr key={`${s.imei}-${s.startRecordedAt}`}>
                  <td>{vehicleName(s.vehicleRegistrationNo, s.imei)}</td>
                  <td className={s.status === "OPEN" ? "warn" : "muted"}>{s.status === "OPEN" ? "Standing now" : "Ended"}</td>
                  <td className="muted">{formatDateTime(s.startRecordedAt)}</td>
                  <td className="right">{formatDuration(s.durationSeconds)}</td>
                  <td className="mono muted">{formatCoordinate(s.latitude, s.longitude)}</td>
                  <td className="right">{formatNumber(s.readingCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}
