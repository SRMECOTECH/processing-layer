"use client";

import { Callout, Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import {
  CsvButton,
  Explain,
  ExplainNote,
  Formula,
  PrintButton,
  PrintHead,
  ReportFiltersBar,
  clock,
  dayLabel,
  duration,
  queryString,
  useReportQuery,
} from "@/components/ops/reports";
import { Gauge, MapPinned, Moon, Route, TriangleAlert } from "@/components/icons";
import { SOURCE_LABEL, classLabel, mapLink, type RoadDayReport } from "@/lib/roads";
import type { ReportFilters } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import s from "@/components/ops/reports.module.css";

const ONE = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 1 });
const TWO = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });

/** The day-end road and speed report: which roads every truck drove, each road's limit, and every over-speed with its road. */
export default function RoadDayReportPage() {
  const [query, change, ready] = useReportQuery();
  const filters = usePoll<ReportFilters>("/api/reports/filters", 60_000);
  const params = queryString({ day: query.date, imei: query.imei });
  const { data: r, error } = usePoll<RoadDayReport>(ready ? `/api/roads/report?${params}` : null, 30_000);

  const totals = r
    ? r.vehicles.reduce(
        (t, v) => ({ km: t.km + v.km, overKm: t.overKm + v.overKm, overMin: t.overMin + v.overMin, events: t.events + v.events }),
        { km: 0, overKm: 0, overMin: 0, events: 0 },
      )
    : null;

  return (
    <div className="stack">
      {r && <PrintHead title="Road & speed day report" subject={`${dayLabel(r.day)} · ${query.imei || "all vehicles"}`} />}
      <PageHeader
        description="For the owner at the end of the day: every road each truck drove, the speed limit of that road for a goods vehicle and where the limit came from, how far and how long the truck was above it, and every confirmed over-speed with the road it happened on."
        meta={[
          ["Day", r ? dayLabel(r.day) : "…"],
          ["Zone", r?.zone ?? "Asia/Kolkata"],
          ["Source", "vehicle_day_road · vehicle_event OVERSPEED · Valhalla road library"],
        ]}
      />
      <ReportFiltersBar
        mode="day"
        query={query}
        onChange={change}
        filters={filters.data}
        actions={
          <>
            {r && (
              <CsvButton
                name={`roads-${r.day}`}
                rows={() => [
                  ["vehicle", "road", "class", "area", "limit km/h", "limit from", "km", "driving min", "avg km/h", "max km/h", "km above", "min above", "max over km/h"],
                  ...r.roads.map((x) => [
                    x.registration ?? x.imei,
                    x.label,
                    x.roadClass,
                    x.area,
                    x.limitKmph,
                    x.limitSource,
                    x.km,
                    x.movingMin,
                    x.avgMovingKmph,
                    x.maxSpeedKmph,
                    x.overKm,
                    x.overMin,
                    x.maxExcessKmph,
                  ]),
                ]}
              />
            )}
            <PrintButton />
          </>
        }
      />
      {error && !r && <Callout tone="warn" title="The report did not load">{error}</Callout>}
      {r && totals && (
        <>
          <Stats columns={5}>
            <Tile label="Driven" icon={Route} value={totals.km} format={(v) => ONE.format(v ?? 0)} unit="km" note={`${r.vehicles.length} vehicles`} />
            <Tile
              label="Above the road's limit"
              icon={Gauge}
              value={totals.overKm}
              format={(v) => TWO.format(v ?? 0)}
              unit="km"
              note={`${ONE.format(totals.overMin)} min`}
              tone={totals.overKm > 0 ? "warn" : undefined}
            />
            <Tile label="Over-speed episodes" icon={TriangleAlert} value={totals.events} tone={totals.events > 0 ? "bad" : undefined} />
            <Tile label="At night" icon={Moon} value={r.nightEvents} note={r.nightHours} />
            <Tile
              label="Readings on a known road"
              icon={MapPinned}
              value={r.dataQuality.onKnownRoadPercent}
              format={(v) => (v == null ? "–" : ONE.format(v))}
              unit="%"
              note={`${r.dataQuality.onKnownRoad} of ${r.dataQuality.readings}`}
              tone={r.dataQuality.onKnownRoadPercent != null && r.dataQuality.onKnownRoadPercent < 80 ? "warn" : undefined}
            />
          </Stats>

          <Card title="Vehicles" subtitle="Above the limit = readings faster than the limit of the road they were on, counted per 100 km so long and short days compare." flush>
            {r.vehicles.length === 0 ? (
              <Empty>No positions on this day.</Empty>
            ) : (
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Vehicle</th>
                      <th>Distance</th>
                      <th>Driving</th>
                      <th>Roads</th>
                      <th>Max speed</th>
                      <th>Above the limit</th>
                      <th>Per 100 km</th>
                      <th>Episodes</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.vehicles.map((v) => (
                      <tr key={v.imei}>
                        <td>
                          <strong>{v.registration ?? v.imei}</strong>
                        </td>
                        <td>{ONE.format(v.km)} km</td>
                        <td>{duration(v.movingMin * 60)}</td>
                        <td>{v.roads}</td>
                        <td>{v.maxSpeedKmph} km/h</td>
                        <td>
                          {TWO.format(v.overKm)} km · {ONE.format(v.overMin)} min
                        </td>
                        <td>{v.overKmPer100Km == null ? "–" : `${ONE.format(v.overKmPer100Km)} km`}</td>
                        <td>{v.events > 0 ? <Tag tone="bad">{v.events}</Tag> : 0}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card title="Over-speed episodes" subtitle="Confirmed by the speed module (sustained, not one spike), judged by the limit of the road where the truck went furthest over it." flush>
            {r.events.length === 0 ? (
              <Empty>No over-speed on this day.</Empty>
            ) : (
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Started</th>
                      <th>Vehicle</th>
                      <th>Road</th>
                      <th>Limit</th>
                      <th>Top speed</th>
                      <th>Over by</th>
                      <th>Lasted</th>
                      <th>Band</th>
                      <th>Where</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.events.map((e) => (
                      <tr key={`${e.imei}-${e.startedAt}`}>
                        <td className="mono">
                          {clock(e.startedAt, r.zone)}
                          {e.night && (
                            <>
                              {" "}
                              <Tag tone="ink">night</Tag>
                            </>
                          )}
                        </td>
                        <td>{e.registration ?? e.imei}</td>
                        <td>
                          {e.roadLabel ?? <span className="muted">road not known</span>}
                          <div className="muted">
                            {e.zoneName ? `geofence ${e.zoneName}` : classLabel(e.roadClass)}
                            {e.roadArea ? ` · ${e.roadArea === "TOWN" ? "town" : "open road"}` : ""}
                          </div>
                        </td>
                        <td>
                          {e.limitKmph ?? "–"} km/h
                          <div className="muted">{e.limitSource ? SOURCE_LABEL[e.limitSource] ?? e.limitSource : ""}</div>
                        </td>
                        <td>{e.maxSpeedKmph ?? "–"} km/h</td>
                        <td>{e.excessKmph == null ? "–" : `+${ONE.format(e.excessKmph)} km/h`}</td>
                        <td>{duration(e.durationSeconds ?? 0)}</td>
                        <td>
                          <Tag tone={e.band === "SEVERE" ? "bad" : e.band === "BREACH" ? "warn" : e.band === "SUSPECT" ? "neutral" : "accent"}>
                            {e.band ?? e.severity}
                          </Tag>
                        </td>
                        <td>
                          <a href={mapLink(e.latitude, e.longitude)} target="_blank" rel="noreferrer">
                            map
                          </a>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card title="Roads driven" subtitle="One row per road and limit, longest first. A road whose limit changes (a town on a highway) shows as two rows." flush>
            {r.roads.length === 0 ? (
              <Empty>No roads on this day.</Empty>
            ) : (
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      {!query.imei && <th>Vehicle</th>}
                      <th>Road</th>
                      <th>Class</th>
                      <th>Limit</th>
                      <th>Distance</th>
                      <th>Driving</th>
                      <th>Avg / max</th>
                      <th>Above the limit</th>
                      <th>Most over</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.roads.map((x) => (
                      <tr key={`${x.imei}-${x.label}-${x.roadClass}-${x.limitKmph}`}>
                        {!query.imei && <td>{x.registration ?? x.imei}</td>}
                        <td>
                          <strong>{x.label}</strong>
                          {x.area && <div className="muted">{x.area === "TOWN" ? "town" : "open road"}</div>}
                        </td>
                        <td>{classLabel(x.roadClass)}</td>
                        <td>
                          {x.limitKmph} km/h
                          <div className="muted">{SOURCE_LABEL[x.limitSource] ?? x.limitSource}</div>
                        </td>
                        <td>{TWO.format(x.km)} km</td>
                        <td>{duration(x.movingMin * 60)}</td>
                        <td>
                          {ONE.format(x.avgMovingKmph)} / {x.maxSpeedKmph} km/h
                        </td>
                        <td className={x.overKm > 0 ? s.warnText : undefined}>
                          {x.overReadings === 0 ? "–" : `${TWO.format(x.overKm)} km · ${ONE.format(x.overMin)} min`}
                        </td>
                        <td>{x.maxExcessKmph > 0 ? `+${x.maxExcessKmph} km/h` : "–"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid-2">
            <Card title="By road class" flush>
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Class</th>
                      <th>Distance</th>
                      <th>Above the limit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.roadClasses.map((c) => (
                      <tr key={c.roadClass}>
                        <td>{classLabel(c.roadClass === "unknown" ? null : c.roadClass)}</td>
                        <td>{ONE.format(c.km)} km</td>
                        <td>{c.overKm > 0 ? `${TWO.format(c.overKm)} km` : "–"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
            <Card title="Roads with most episodes" subtitle="Where several trucks speed, a sign or a stricter limit helps more than talking to one driver." flush>
              {r.hotspots.length === 0 ? (
                <Empty>No episodes.</Empty>
              ) : (
                <div className="data-table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Road</th>
                        <th>Episodes</th>
                        <th>Vehicles</th>
                        <th>Worst</th>
                      </tr>
                    </thead>
                    <tbody>
                      {r.hotspots.map((h) => (
                        <tr key={h.road}>
                          <td>{h.road}</td>
                          <td>{h.events}</td>
                          <td>{h.vehicles}</td>
                          <td>+{ONE.format(h.worstExcessKmph)} km/h</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
          </div>

          <Explain>
            <Formula
              label="Road under a reading"
              how="the nearest road piece of the road library within 35 m; the library is Valhalla's map matching of the trips on the all-India OpenStreetMap"
              result="road, class, lanes, town or open road"
            />
            <Formula
              label="Limit of a reading"
              how="lowest of: the company's limit for the vehicle, the road's limit (set by hand > OpenStreetMap > road table by class and area), a geofence's limit, the night reduction"
              result="km/h, with where it came from"
            />
            <Formula label="Above the limit" how="sum of the distance and time of the legs ending at a reading faster than its limit (gaps over 10 min left out)" result="km · min" />
            <Formula label="Episode" how="speed module: over the limit for 3 readings in a row and 60 s, ends when the speed drops 5 km/h under it" result="one alert per episode" />
            <ExplainNote>
              &quot;Above the limit&quot; counts every fast reading (exposure); an episode needs the truck to stay fast. A truck can be above the limit
              for a few readings without an episode, never the other way round.
            </ExplainNote>
          </Explain>
        </>
      )}
    </div>
  );
}
