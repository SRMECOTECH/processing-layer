"use client";

import { useState, type ReactNode } from "react";
import { Clock, Grid3x3, MapPinned, Sparkles, Timer, Truck } from "@/components/icons";
import { Callout, Card, Empty, Stats, Tag, Tile } from "@/components/ops/kit";
import { Explain, ExplainNote, Formula, clock } from "@/components/ops/reports";
import { ROLE_LABEL, roleTone, stay, type DayReportVehicle, type FenceRole, type GeofenceDayReport } from "@/lib/geofence";
import s from "./day-report.module.css";

const TRUCKS_SHOWN = 25;
const ROLES: FenceRole[] = ["ORIGIN", "DESTINATION", "YARD", "CHECKPOINT", "ZONE"];

/**
 * The day-wise geofence report, the same for the GPS provider (console) and a transporter or consigner (portal):
 * the answer to "where did the day go, and where did my trucks wait too long?".
 */
export function GeofenceDayReportView({ report: r, actions }: { report: GeofenceDayReport; actions?: ReactNode }) {
  const h = r.headline;
  return (
    <div className="stack">
      {r.partial && <Callout title="The day is not over">Numbers run up to now; trucks still inside a fence keep adding time.</Callout>}

      <Stats columns={4}>
        <Tile label="Visits" icon={MapPinned} value={h.visits} note={`${h.passes} drive-throughs not counted`} big />
        <Tile label="Trucks" icon={Truck} value={h.vehicles} note={`at ${h.fencesVisited} fence${h.fencesVisited === 1 ? "" : "s"}`} big />
        <Tile label="Time inside fences" icon={Clock} value={stay(h.minutesInside)} note={`median stay ${stay(h.medianStayMinutes)}`} big />
        <Tile
          label="Over the dwell limit"
          icon={Timer}
          value={h.longStays}
          tone={h.longStays > 0 ? "warn" : undefined}
          note={`${h.stillInside} still inside${h.insideWhenLastSeen ? ` · ${h.insideWhenLastSeen} with no signal` : ""}${h.uncertainStamps ? ` · ${h.uncertainStamps} uncertain ±` : ""}`}
          big
        />
      </Stats>

      <Card icon={Sparkles} title="What stands out" subtitle="Worst first: stays far above each fence's usual, slow fences, trucks still held." actions={actions}>
        <div className={s.insights}>
          {r.insights.map((i, n) => (
            <div key={n} className={`${s.insight} ${i.severity === "HIGH" ? s.high : i.severity === "MEDIUM" ? s.medium : ""}`}>
              <Tag tone={i.severity === "HIGH" ? "bad" : i.severity === "MEDIUM" ? "warn" : "neutral"}>{i.severity === "INFO" ? "NOTE" : i.severity}</Tag>
              <span>{i.text}</span>
            </div>
          ))}
        </div>
      </Card>

      <FencesCard report={r} />
      <CrowdingCard report={r} />
      <TrucksCard report={r} />

      <Card title="How this report is made">
        <Explain title="Visits, stamps and the usual stay">
          <Formula label="Visit" how={String(r.method.visit)} result="VISIT" />
          <Formula label="Stamps" how={String(r.method.stamps)} result="observed times" />
          <Formula label="Day share" how={String(r.method.dayShare)} result="minutes in the day" />
          <Formula label="Usual" how={String(r.method.usual)} result="vs usual = today's median ÷ usual median" />
          <Formula label="Uncertain ±" how={String(r.method.uncertain)} result="shown with ±" />
          <ExplainNote>
            Medians, not averages: a few multi-day holds would otherwise set the &quot;typical&quot; stay. Nested fences (a plant inside its
            town) are counted once in a truck&apos;s time inside fences, and separately per fence.
          </ExplainNote>
        </Explain>
      </Card>
    </div>
  );
}

function FencesCard({ report: r }: { report: GeofenceDayReport }) {
  const total = r.fences.reduce((sum, f) => sum + f.minutesInside, 0) || 1;
  return (
    <Card icon={Grid3x3} title="Where the time went" subtitle="Per fence, most time first. 'vs usual' compares today's median stay with the fence's own last 30 days." flush>
      {r.fences.length === 0 ? (
        <Empty>No fence was visited.</Empty>
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Fence</th>
                <th className="right">Visits</th>
                <th className="right">Trucks</th>
                <th>Time inside</th>
                <th className="right">Median stay</th>
                <th className="right">90% within</th>
                <th>Longest</th>
                <th className="right">vs usual</th>
                <th className="right">Still inside</th>
                <th className="right">Passes</th>
              </tr>
            </thead>
            <tbody>
              {r.fences.map((f) => (
                <tr key={f.fenceId}>
                  <td>
                    <strong>{f.name}</strong>
                    <div>
                      <Tag tone={roleTone(f.role)}>{ROLE_LABEL[f.role] ?? f.role}</Tag>
                    </div>
                  </td>
                  <td className="right mono">{f.visits}</td>
                  <td className="right mono">{f.vehicles}</td>
                  <td>
                    <span className="mono">{stay(f.minutesInside)}</span>
                    <span className={s.share} title={`${Math.round((100 * f.minutesInside) / total)}% of all time inside fences`}>
                      <span className={s.shareFill} style={{ width: `${(100 * f.minutesInside) / total}%` }} />
                    </span>
                  </td>
                  <td className="right mono">{stay(f.medianStayMinutes)}</td>
                  <td className="right mono">{stay(f.p90StayMinutes)}</td>
                  <td>
                    <span className="mono">{stay(f.longestStayMinutes)}</span>
                    {f.longestVehicle && <div className="muted small">{f.longestVehicle}</div>}
                  </td>
                  <td className="right">
                    {f.vsUsual === null ? (
                      <span className="faint" title="Fewer than a few closed visits in the last 30 days">no history</span>
                    ) : (
                      <span title={`usual median ${stay(f.usualMedianMinutes)} over ${f.usualVisits} visits`}>
                        <Tag tone={f.vsUsual >= 2.5 ? "bad" : f.vsUsual >= 1.5 ? "warn" : "good"} mono>
                          {f.vsUsual.toFixed(1)}×
                        </Tag>
                      </span>
                    )}
                  </td>
                  <td className="right mono">{f.stillInside || <span className="faint">0</span>}</td>
                  <td className="right mono">{f.passes || <span className="faint">0</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function CrowdingCard({ report: r }: { report: GeofenceDayReport }) {
  if (r.occupancy.length === 0) return null;
  const peak = Math.max(...r.occupancy.flatMap((o) => o.averageInsideByHour), 0.01);
  return (
    <Card icon={Clock} title="When the fences were crowded" subtitle="Average trucks inside, hour by hour (darker = more). A long dark band is a queue at the gate.">
      <div className={s.heat}>
        <span />
        {Array.from({ length: 24 }, (_, hour) => (
          <span key={hour} className={s.heatHour}>
            {hour % 3 === 0 ? String(hour).padStart(2, "0") : ""}
          </span>
        ))}
        {r.occupancy.map((o) => (
          <Row key={o.fenceId} name={o.name} peakHour={o.peakHour} peak={o.peakAverageInside}>
            {o.averageInsideByHour.map((value, hour) => (
              <span
                key={hour}
                className={s.heatCell}
                style={{ opacity: value <= 0 ? 0.06 : 0.18 + (0.82 * value) / peak }}
                title={`${String(hour).padStart(2, "0")}:00–${String(hour + 1).padStart(2, "0")}:00 · ${value.toFixed(2)} trucks inside on average`}
              />
            ))}
          </Row>
        ))}
      </div>
    </Card>
  );
}

function Row({ name, peakHour, peak, children }: { name: string; peakHour: number; peak: number; children: ReactNode }) {
  return (
    <>
      <span className={s.heatName} title={`busiest ${String(peakHour).padStart(2, "0")}:00 with ${peak.toFixed(1)} trucks on average`}>
        {name}
      </span>
      {children}
    </>
  );
}

function TrucksCard({ report: r }: { report: GeofenceDayReport }) {
  const [all, setAll] = useState(false);
  const shown = all ? r.vehicles : r.vehicles.slice(0, TRUCKS_SHOWN);
  return (
    <Card icon={Truck} title="Each truck's day" subtitle={`Midnight to midnight (${r.zone}). Bars are stays inside fences; a red outline is over the fence's dwell limit.`}>
      {r.vehicles.length === 0 ? (
        <Empty>No truck visited a fence.</Empty>
      ) : (
        <>
          <div className={s.legend}>
            {ROLES.filter((role) => r.vehicles.some((v) => v.timeline.some((t) => t.role === role))).map((role) => (
              <span key={role}>
                <span className={`${s.swatch} ${s[role]}`} />
                {ROLE_LABEL[role]}
              </span>
            ))}
          </div>
          <div className={s.trucks}>
            {shown.map((v) => (
              <TruckRow key={v.imei} v={v} zone={r.zone} />
            ))}
          </div>
          {r.vehicles.length > TRUCKS_SHOWN && (
            <button type="button" className={`btn soft ${s.more}`} onClick={() => setAll(!all)}>
              {all ? "Show fewer" : `Show all ${r.vehicles.length} trucks`}
            </button>
          )}
        </>
      )}
    </Card>
  );
}

function TruckRow({ v, zone }: { v: DayReportVehicle; zone: string }) {
  const roles = Object.entries(v.minutesByRole)
    .filter(([, m]) => (m ?? 0) > 0)
    .map(([role, m]) => `${ROLE_LABEL[role as FenceRole] ?? role} ${stay(m)}`);
  return (
    <div className={s.truck}>
      <div>
        <div className={s.truckName}>{v.vehicleRegistrationNo ?? `Device ${v.imei}`}</div>
        <div className={s.truckFacts}>
          {stay(v.minutesInside)} inside fences · {v.visits} visit{v.visits === 1 ? "" : "s"}
          {v.passes ? ` · ${v.passes} pass${v.passes === 1 ? "" : "es"}` : ""}
          <br />
          {roles.join(" · ")}
          {v.day && (
            <>
              <br />
              driving {stay(v.day.movingMinutes)} · {v.day.distanceKm} km
            </>
          )}
          {v.stillInside.length > 0 && (
            <>
              <br />
              still inside {v.stillInside.join(", ")}
            </>
          )}
          {v.silentMinutes > 0 && (
            <>
              <br />
              no GPS signal for {stay(v.silentMinutes)}: stays end at its last reading
            </>
          )}
        </div>
      </div>
      <div>
        <div className={s.track}>
          {v.timeline.map((t, n) => (
            <span
              key={n}
              className={`${s.segment} ${s[t.role] ?? ""} ${t.overLimit ? s.over : ""} ${t.open ? s.open : ""}`}
              style={{ left: `${(100 * t.startMinute) / 1440}%`, width: `${Math.max(0.2, (100 * (t.endMinute - t.startMinute)) / 1440)}%` }}
              title={`${t.name}: ${t.startedBefore ? "since the day before" : clock(t.enteredAt, zone)} → ${t.noSignalMinutes ? `inside when last heard, ${stay(t.noSignalMinutes)} ago` : t.open ? "still inside" : clock(t.exitedAt, zone)} · stay ${stay(t.stayMinutes)}${t.overLimit ? " · over the dwell limit" : ""}${t.uncertainMinutes ? ` · ±${t.uncertainMinutes} min (GPS gap)` : ""}`}
            >
              {t.endMinute - t.startMinute >= 90 ? t.name : ""}
            </span>
          ))}
        </div>
        <div className={s.ticks}>
          {[0, 6, 12, 18, 24].map((hour) => (
            <span key={hour} className={s.tick} style={{ left: `${(100 * hour) / 24}%` }}>
              {String(hour).padStart(2, "0")}:00
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
