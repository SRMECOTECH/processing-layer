"use client";

import { History, ListChecks, Map as MapIcon } from "@/components/icons";
import { Card, Empty, KeyValues, Tag } from "@/components/ops/kit";
import { EtaStrip } from "@/components/routes/eta-strip";
import { RouteMap } from "@/components/routes/route-map";
import {
  ROUTE_EVENT_LABEL,
  STOP_LABEL,
  clock,
  eventSummary,
  lateText,
  lateTone,
  minutesText,
  stopTone,
  tripTone,
  type TripDetail,
} from "@/lib/routes";
import styles from "./routes.module.css";

const severityTone = (s: string) => (s === "CRITICAL" || s === "HIGH" ? "bad" : s === "MEDIUM" ? "warn" : s === "LOW" ? "neutral" : "accent");

/** One trip in full: its strip, the map, the numbers, the stops and what happened on the way. */
export function TripView({ trip: t }: { trip: TripDetail }) {
  const tone = tripTone(t);
  const manual = t.stops.filter((s) => s.kind === "MANUAL");
  const fences = t.stops.filter((s) => s.kind === "FENCE");
  return (
    <div className="stack">
      <EtaStrip trip={t} />

      <div className="grid-main-side">
        <Card icon={MapIcon} title="Route and trail" subtitle={`${t.plannedKm} km planned by ${t.engine}${t.version > 1 ? ` · re-planned ${t.version - 1}×` : ""}`} live={t.status === "ACTIVE"}>
          <RouteMap
            line={t.line}
            firstLine={t.firstLine}
            trail={t.trail}
            stops={t.stops}
            events={t.events}
            origin={{ lat: t.originLat, lon: t.originLon, name: t.origin }}
            destination={{ lat: t.destinationLat, lon: t.destinationLon, name: t.destination }}
            truckTone={tone}
            label={`${t.vehicle}: ${t.origin} to ${t.destination}`}
          />
        </Card>
        <Card title="The trip in numbers" live={t.status === "ACTIVE"}>
          <KeyValues
            rows={[
              ["Vehicle", <span className="mono" key="v">{t.vehicle}</span>],
              ...(t.tripNo ? ([["Trip", <span className="mono" key="t">{t.tripNo}</span>]] as [string, React.ReactNode][]) : []),
              ...(t.consignee ? ([["Consignee", t.consignee]] as [string, React.ReactNode][]) : []),
              ["Planned", `${t.plannedKm} km · ${minutesText(t.plannedMinutes)} of driving`],
              ["Left", clock(t.departedAt)],
              ["Promised ETA", clock(t.promisedEtaAt)],
              [t.arrivedAt ? "Arrived" : "ETA now", <strong key="e">{clock(t.arrivedAt ?? t.etaAt)}</strong>],
              ["Against the promise", <Tag key="l" tone={lateTone(t.lateMinutes)}>{lateText(t.lateMinutes)}</Tag>],
              ["Pace", `${t.pace.toFixed(2)}× the planned driving time`],
              ["Done", `${t.doneKm} km (${Math.round(t.progress * 100)} %)`],
              ["Off the road now", t.crossTrackMeters != null ? `${t.crossTrackMeters} m` : "–"],
              ["Deviations", `${t.deviations} · ${t.deviationKm} km extra · ${minutesText(t.deviationMinutes)}`],
              ["Wrong direction", `${t.wrongWays}×`],
              ["Last reading", clock(t.lastReadingAt)],
            ]}
          />
        </Card>
      </div>

      <Card icon={ListChecks} title="Stops on the way" subtitle={`${manual.length} planned, ${fences.length} geofences the road crosses`} flush>
        {t.stops.length ? (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Stop</th>
                  <th>Kind</th>
                  <th className="right">km</th>
                  <th>Planned</th>
                  <th>ETA / arrived</th>
                  <th>Left</th>
                  <th className="right">Stay</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {t.stops.map((s) => (
                  <tr key={s.seq}>
                    <td className="mono">{s.seq}</td>
                    <td>{s.name}</td>
                    <td>{s.kind === "MANUAL" ? "Planned stop" : "Geofence on the way"}</td>
                    <td className="right mono">{s.km ?? "–"}</td>
                    <td className="mono">{clock(s.plannedAt)}</td>
                    <td className="mono">{s.arrivedAt ? clock(s.arrivedAt) : s.etaAt ? `≈ ${clock(s.etaAt)}` : "–"}</td>
                    <td className="mono">{clock(s.departedAt)}</td>
                    <td className="right mono">{s.minutesInside != null ? minutesText(s.minutesInside) : s.stayMinutes ? `plan ${s.stayMinutes} min` : "–"}</td>
                    <td>
                      <Tag tone={stopTone(s.status)}>{STOP_LABEL[s.status]}</Tag> {s.outOfOrder && <Tag tone="warn">OUT OF ORDER</Tag>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty>No stops on this trip.</Empty>
        )}
      </Card>

      <Card icon={History} title="What happened" subtitle="Route, ETA and stop events of this trip, oldest first.">
        {t.events.length ? (
          <div className={styles.timeline}>
            {t.events.map((e, i) => (
              <div className={styles.event} key={i}>
                <span className="mono">{clock(e.startedAt)}</span>
                <span>
                  <Tag tone={severityTone(e.severity)}>{ROUTE_EVENT_LABEL[e.type] ?? e.type}</Tag> {e.status === "OPEN" && <Tag tone="bad">OPEN</Tag>}
                </span>
                <span>{eventSummary(e)}</span>
              </div>
            ))}
          </div>
        ) : (
          <Empty>Nothing yet: no deviation, wrong direction, ETA change or stop event on this trip.</Empty>
        )}
      </Card>
    </div>
  );
}
