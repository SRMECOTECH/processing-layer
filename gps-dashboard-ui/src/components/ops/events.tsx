"use client";

import Link from "next/link";
import { Empty, Tag, type Tone } from "./kit";
import { formatDateTime, formatDuration, formatTime, vehicleName } from "@/lib/format";
import type { VehicleEventView } from "@/lib/types";

export const EVENT_LABEL: Record<VehicleEventView["type"], string> = {
  OVERSPEED: "Over-speed",
  SPEED_SENSOR_MISMATCH: "Speedometer vs GPS",
  STOPPAGE: "Stoppage",
  PARKED: "Parked",
  IDLING: "Idling",
  IGNITION_ON: "Ignition on",
  IGNITION_OFF: "Ignition off",
  HARSH_ACCELERATION: "Harsh acceleration",
  HARSH_BRAKING: "Harsh braking",
};

export function severityTone(severity: VehicleEventView["severity"]): Tone {
  switch (severity) {
    case "CRITICAL":
    case "HIGH":
      return "bad";
    case "MEDIUM":
      return "warn";
    case "LOW":
      return "accent";
    default:
      return "neutral";
  }
}

export function eventDuration(e: VehicleEventView): number | null {
  const fromDetails = e.details.durationSeconds ?? e.details.idlingSeconds;
  if (typeof fromDetails === "number" && fromDetails > 0) return fromDetails;
  if (e.endedAt) return (new Date(e.endedAt).getTime() - new Date(e.startedAt).getTime()) / 1000;
  return null;
}

/** One line of the most useful details for each event type. */
export function eventSummary(e: VehicleEventView): string {
  const d = e.details as Record<string, unknown>;
  switch (e.type) {
    case "OVERSPEED":
      return `max ${d.maxSpeedKmph} km/h over ${d.limitKmph} (${d.ruleScope}), ${String(d.verdict ?? "").toLowerCase().replace("_", " ")}`;
    case "SPEED_SENSOR_MISMATCH":
      return `speedometer ${d.speedometerKmph} km/h, GPS ${d.gpsSpeedKmph} km/h`;
    case "STOPPAGE": {
      const verdict = String(d.contextVerdict ?? "PENDING").replaceAll("_", " ").toLowerCase();
      const weather = (d.weather as Record<string, unknown> | undefined)?.description;
      return `engine ${String(d.ignition ?? "?").toLowerCase()} · ${verdict}${weather ? ` · ${weather}` : ""}`;
    }
    case "PARKED":
      return "engine off, neutralised (no alarm)";
    case "IDLING":
      return `engine running while standing (${String(d.source ?? "").toLowerCase()} ignition)`;
    case "IGNITION_ON":
    case "IGNITION_OFF":
      return `${String(d.source ?? "").toLowerCase()} ignition`;
    case "HARSH_ACCELERATION":
    case "HARSH_BRAKING":
      return `${d.accelerationMps2} m/s² (${d.fromSpeedKmph} to ${d.toSpeedKmph} km/h)`;
  }
}

export function EventTable({ events, empty = "No events yet.", showType = true }: { events: VehicleEventView[] | undefined; empty?: string; showType?: boolean }) {
  if (!events) return <Empty>Waiting for the service.</Empty>;
  if (events.length === 0) return <Empty>{empty}</Empty>;
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            {showType && <th>Event</th>}
            <th>Vehicle</th>
            <th>Started</th>
            <th className="right">Duration</th>
            <th>Details</th>
            <th>Severity</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => {
            const duration = eventDuration(e);
            return (
              <tr key={e.id} className={e.severity === "CRITICAL" || e.severity === "HIGH" ? "flagged" : undefined}>
                {showType && <td>{EVENT_LABEL[e.type] ?? e.type}</td>}
                <td>
                  <Link className="link mono" href={`/live/inspector?imei=${e.imei}`}>
                    {vehicleName(e.vehicleRegistrationNo, e.imei)}
                  </Link>
                </td>
                <td className="muted">{formatDateTime(e.startedAt)}</td>
                <td className="right mono">{duration === null ? "–" : formatDuration(duration)}</td>
                <td className="muted" style={{ whiteSpace: "normal", minWidth: 220 }}>{eventSummary(e)}</td>
                <td>
                  <Tag tone={severityTone(e.severity)}>{e.severity}</Tag>
                </td>
                <td>{e.status === "OPEN" ? <Tag tone="ink">ONGOING</Tag> : <span className="faint">ended {e.endedAt ? formatTime(e.endedAt) : ""}</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** A short list of events for side panels: severity, what, which vehicle, when. */
export function EventFeed({ events, empty = "No events yet." }: { events: VehicleEventView[] | undefined; empty?: string }) {
  if (!events) return <Empty>Waiting for the service.</Empty>;
  if (events.length === 0) return <Empty>{empty}</Empty>;
  return (
    <ul className="feed">
      {events.map((e) => {
        const tone = severityTone(e.severity);
        return (
          <li key={e.id} className="feed-row">
            <span className={`status-dot ${tone === "bad" ? "bad" : tone === "warn" ? "warn" : tone === "accent" ? "accent" : "muted"}`} />
            <span className="feed-main">
              <span className="feed-title">
                {EVENT_LABEL[e.type] ?? e.type}
                <Link className="link mono" href={`/live/inspector?imei=${e.imei}`}>
                  {vehicleName(e.vehicleRegistrationNo, e.imei)}
                </Link>
              </span>
              <span className="feed-sub">{eventSummary(e)}</span>
            </span>
            <span className="feed-side">
              {e.status === "OPEN" ? <Tag tone="ink">ONGOING</Tag> : <span className="faint">{formatTime(e.startedAt)}</span>}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
