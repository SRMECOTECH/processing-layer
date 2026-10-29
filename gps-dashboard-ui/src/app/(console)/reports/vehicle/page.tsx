"use client";

import { Clock, ListChecks, ParkingSquare, Route, Sigma, Truck } from "@/components/icons";
import { Callout, Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import {
  CsvButton,
  Explain,
  Formula,
  FuelSteps,
  PrintButton,
  PrintHead,
  ReportFiltersBar,
  clock,
  dayLabel,
  duration,
  hours,
  kms,
  litres,
  queryString,
  reportStyles as s,
  rupees,
  useReportQuery,
  vehicleLabel,
} from "@/components/ops/reports";
import { humanize } from "@/lib/format";
import type { ReportFilters, TimelineEntry, VehicleDaySheet } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

/** One vehicle, one day: where it drove, where and how long it stood, with the engine on or off. */
export default function VehicleDaySheetPage() {
  const [query, change, ready] = useReportQuery();
  const filters = usePoll<ReportFilters>("/api/reports/filters", 60_000);
  const path = ready && query.imei ? `/api/reports/vehicle-day?${queryString({ imei: query.imei, date: query.date })}` : null;
  const { data: d, error } = usePoll<VehicleDaySheet>(path, 30_000);
  const choice = filters.data?.vehicles.find((v) => v.imei === query.imei);
  const name = choice?.registration ?? query.imei;

  return (
    <div className="stack">
      {d && <PrintHead title="Vehicle day sheet" subject={`${name} · ${dayLabel(d.day)}`} />}
      <PageHeader
        description="Everything one vehicle did on one day, in time order: each trip with its distance and speed, each stop with its place, length and whether the engine kept running."
        meta={[
          ["Vehicle", name || "choose one"],
          ["Day", query.date ? dayLabel(query.date) : "…"],
        ]}
      />

      <Card title="Choose the vehicle and day">
        <ReportFiltersBar
          mode="vehicle"
          query={query}
          onChange={change}
          filters={filters.data}
          actions={
            <>
              <PrintButton />
              {d && <CsvButton name={`day-sheet-${name}-${d.day}.csv`} rows={() => csvRows(d)} />}
            </>
          }
        />
      </Card>

      {!query.imei ? (
        <Callout title="Choose a vehicle">Pick one in the Vehicle drop-down, or open a vehicle's row in the Day report.</Callout>
      ) : !d ? (
        <Empty>{error ?? "Loading the vehicle's day."}</Empty>
      ) : !d.summary ? (
        <Callout title="No readings that day">{name} did not report on {dayLabel(d.day)}.</Callout>
      ) : (
        <>
          <SheetTiles d={d} />
          <Card icon={ListChecks} title="The day in order" subtitle={`${d.timeline.length} trips and stops · times in ${d.zone}`}>
            {d.timeline.length === 0 ? (
              <p className="muted">No trip or stop was detected that day.</p>
            ) : (
              <div className={s.timeline}>
                {d.timeline.map((e) => (
                  <Entry key={`${e.kind}-${e.start}`} e={e} zone={d.zone} />
                ))}
              </div>
            )}
            <Explain title="How trips and stops are found">
              <Formula label="Stop" how="the vehicle stays within 200 m for at least 3 minutes (trip detector)" result="start – end" />
              <Formula label="Trip" how="from leaving a stop (moving 200 m away) to the next stop" result="km, speed" />
              <Formula label="Engine on" how="a STOPPAGE or IDLING episode overlaps the stop (ignition from the device, or virtual)" result="ON / OFF" />
              <Formula label="Idling in a stop" how="the part of IDLING episodes inside the stop" result="min" />
            </Explain>
          </Card>
          {d.events.length > 0 && (
            <Card title="Events that day" subtitle="From the stream processors" flush>
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Event</th>
                      <th>Severity</th>
                      <th>Lasted</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.events.map((e, i) => (
                      <tr key={`${e.type}-${e.startedAt}-${i}`}>
                        <td className="mono">
                          {clock(e.startedAt, d.zone)}
                          {e.endedAt ? ` – ${clock(e.endedAt, d.zone)}` : ""}
                        </td>
                        <td>{humanize(e.type)}</td>
                        <td>
                          <Tag tone={e.severity === "HIGH" ? "bad" : e.severity === "MEDIUM" ? "warn" : "neutral"}>{e.severity}</Tag>
                        </td>
                        <td>{e.seconds !== null ? duration(e.seconds) : "–"}</td>
                        <td>{e.status === "OPEN" ? "still going" : "ended"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
}

function SheetTiles({ d }: { d: VehicleDaySheet }) {
  const v = d.summary!;
  const a = d.assumptions;
  return (
    <Stats columns={3}>
      <Tile
        label="Distance"
        icon={Route}
        value={kms(v.distanceKm)}
        note={`${v.trips} trips · max ${v.maxSpeedKmph} km/h`}
        foot={
          <Explain>
            <Formula label="Distance" how="sum of the distance between this vehicle's consecutive readings" result={kms(v.distanceKm)} />
            <Formula label="Average speed" how={`${kms(v.distanceKm)} ÷ ${hours(v.movingSeconds)} driving`} result={`${v.avgMovingSpeedKmph.toFixed(1)} km/h`} />
          </Explain>
        }
      />
      <Tile
        label="On the road"
        icon={Clock}
        value={`${clock(v.firstSeen, d.zone)} – ${clock(v.lastSeen, d.zone)}`}
        note={`driving ${duration(v.movingSeconds)}`}
        foot={
          <Explain>
            <Formula label="First / last" how="the day's first and last stored reading" result={`${clock(v.firstSeen, d.zone)} – ${clock(v.lastSeen, d.zone)}`} />
            <Formula label="Driving" how="time between readings while MOVING" result={duration(v.movingSeconds)} />
            <Formula label="Standing" how="time between readings while STOPPED" result={duration(v.stoppedSeconds)} />
          </Explain>
        }
      />
      <Tile
        label="Stops"
        icon={Truck}
        value={String(v.stops)}
        note={`${duration(v.stopSeconds)} standing · ${v.engineOnStops} engine on`}
        foot={
          <Explain>
            <Formula label="Stops" how="stops that started that day" result={v.stops} />
            <Formula label="Time in stops" how="each stop's length, cut to the day" result={duration(v.stopSeconds)} />
          </Explain>
        }
      />
      <Tile
        label="Engine idling"
        icon={ParkingSquare}
        value={duration(v.idleSeconds)}
        note={`${litres(v.fuel.idleLitres)} · ${rupees(v.fuel.idleLitres * a.pricePerLitre)}`}
        tone={v.idleSeconds >= 3600 ? "warn" : undefined}
        foot={
          <Explain>
            <Formula label="Idling" how="IDLING episodes (engine on, standing past 5 min)" result={hours(v.idleSeconds)} />
            <Formula label="Fuel" how={`${hours(v.idleSeconds)} × ${a.idleLitresPerHour} L/h`} result={litres(v.fuel.idleLitres)} />
          </Explain>
        }
      />
      <Tile
        label="Fuel used (estimate)"
        icon={Route}
        value={rupees(v.fuel.cost)}
        note={litres(v.fuel.usedLitres)}
        foot={
          <Explain>
            <FuelSteps row={v} a={a} />
          </Explain>
        }
      />
      <Tile
        label="Could have been saved"
        icon={Sigma}
        tone="good"
        value={rupees(v.fuel.savableCost)}
        note={litres(v.fuel.savableLitres)}
        foot={
          <Explain>
            <Formula label="Idling" how={litres(v.fuel.idleLitres)} result={rupees(v.fuel.idleLitres * a.pricePerLitre)} />
            <Formula label="Fast driving" how={`${kms(v.fastKm)} above ${a.economicalSpeedKmph} km/h`} result={rupees(v.fuel.fastLitres * a.pricePerLitre)} />
          </Explain>
        }
      />
    </Stats>
  );
}

function Entry({ e, zone }: { e: TimelineEntry; zone: string }) {
  const place = e.latitude !== null && e.longitude !== null ? `${e.latitude.toFixed(5)}, ${e.longitude.toFixed(5)}` : "";
  return (
    <div className={s.entry} data-kind={e.kind} data-engine={e.kind === "STOP" ? e.engine ?? "UNKNOWN" : undefined}>
      <span className={s.entryTime}>
        {clock(e.start, zone)} – {clock(e.end, zone)}
      </span>
      <span className={s.entryDot}>
        <i />
      </span>
      <span>
        {e.kind === "TRIP" ? (
          <>
            <span className={s.entryTitle}>Trip · {kms(e.distanceKm)}</span>
            <span className={s.entrySub}>
              {" "}
              · average {e.avgSpeedKmph?.toFixed(0)} km/h, max {e.maxSpeedKmph} km/h{e.status === "OPEN" ? " · still driving" : ""}
            </span>
          </>
        ) : (
          <>
            <span className={s.entryTitle}>Stop · {duration(e.seconds)}</span>
            <span className={s.entrySub}>
              {" "}
              · {e.engine === "ON" ? `engine on${e.idleSeconds > 0 ? `, idling ${duration(e.idleSeconds)}` : ""}` : e.engine === "OFF" ? "engine off" : "engine not known"}
              {e.status === "OPEN" ? " · still standing" : ""}
            </span>
          </>
        )}
      </span>
      <span className={`${s.entryRight} mono`} title={e.kind === "TRIP" ? "Where the trip ended" : "Where it stood"}>
        {place}
      </span>
    </div>
  );
}

function csvRows(d: VehicleDaySheet): (string | number)[][] {
  return [
    ["Vehicle", "Day", "Kind", "Start", "End", "Minutes", "Distance km", "Max speed km/h", "Engine", "Idling min", "Latitude", "Longitude"],
    ...d.timeline.map((e) => [
      vehicleLabel({ registration: d.summary?.registration ?? null, imei: d.imei }), d.day, e.kind, clock(e.start, d.zone), clock(e.end, d.zone),
      e.seconds / 60, e.distanceKm ?? "", e.maxSpeedKmph ?? "", e.engine ?? "", e.idleSeconds / 60, e.latitude ?? "", e.longitude ?? "",
    ]),
  ];
}
