"use client";

import Link from "next/link";
import { Truck } from "@/components/icons";
import { Tag } from "@/components/ops/kit";
import { STOP_LABEL, clock, lateText, lateTone, minutesText, tripLabel, tripTone, type Trip, type TripStop } from "@/lib/routes";
import styles from "./routes.module.css";

const pct = (v: number) => `${Math.max(0, Math.min(1, v)) * 100}%`;

function stopClass(s: TripStop, progress: number): string {
  const kind = s.kind === "FENCE" ? styles.fence : "";
  if (s.status === "MISSED") return `${styles.dot} ${styles.missed} ${kind}`;
  if (s.status === "ARRIVED" || s.status === "INSIDE") return `${styles.dot} ${styles.here} ${kind}`;
  if (s.status === "DEPARTED" || s.status === "PASSED" || (s.progress !== null && s.progress < progress && s.kind === "FENCE")) {
    return `${styles.dot} ${styles.done} ${kind}`;
  }
  return `${styles.dot} ${kind}`;
}

function stopTitle(s: TripStop): string {
  const when = s.arrivedAt ? `arrived ${clock(s.arrivedAt)}` : s.etaAt ? `ETA ${clock(s.etaAt)}` : s.plannedAt ? `planned ${clock(s.plannedAt)}` : "";
  return `${s.name} · ${STOP_LABEL[s.status]}${s.km !== null ? ` · km ${s.km}` : ""}${when ? ` · ${when}` : ""}`;
}

/** What is wrong right now, in a few words, or null. */
function alertOf(t: Trip): string | null {
  if (t.wrongWay) return "Driving the wrong way";
  if (t.routeStatus === "OFF_ROUTE") {
    return `Off route${t.deviationAddedMinutes ? `: about ${minutesText(t.deviationAddedMinutes)} longer this way` : ""}${t.crossTrackMeters ? ` · ${(t.crossTrackMeters / 1000).toFixed(1)} km from the road` : ""}`;
  }
  if (t.routeStatus === "SUSPECT") return `Left the planned road ${t.crossTrackMeters ? `(${t.crossTrackMeters} m away)` : ""}, checking the way it is going`;
  if (t.noSignal && t.status === "ACTIVE") return `No reading for ${minutesText(t.silentMinutes)}`;
  return null;
}

/**
 * One truck's trip as a line: origin ● ── stops ── destination ◎, the truck at its progress, coloured by state
 * (green on route, amber checking, red off route or wrong way), with the live ETA against the one promised at departure.
 */
export function EtaStrip({ trip: t, href, onSelect, selected }: { trip: Trip; href?: string; onSelect?: () => void; selected?: boolean }) {
  const tone = tripTone(t);
  const arrived = t.routeStatus === "ARRIVED";
  const alert = alertOf(t);
  const body = (
    <>
      <div className={styles.head}>
        <div className={styles.who}>
          <span className={styles.vehicle}>{t.vehicle}</span>
          <Tag tone={tone}>{tripLabel(t)}</Tag>
          <span className={styles.route}>
            {t.tripNo ? `Trip ${t.tripNo} · ` : ""}
            {t.origin ?? "Start"} → {t.destination ?? "Destination"}
          </span>
        </div>
        <div className={styles.eta}>
          <span className={styles.etaLabel}>{arrived ? "Arrived" : t.routeStatus === "WAITING" ? "If it left now" : "ETA"}</span>
          <span className={styles.etaTime}>{clock(arrived ? t.arrivedAt : t.etaAt)}</span>
          {t.routeStatus !== "WAITING" && t.lateMinutes !== null && <Tag tone={lateTone(t.lateMinutes)}>{lateText(t.lateMinutes)}</Tag>}
        </div>
      </div>
      <div className={styles.track} aria-label={`${Math.round(t.progress * 100)} % of ${t.plannedKm} km`}>
        <span className={styles.rail} />
        <span className={styles.fill} style={{ width: pct(t.progress) }} />
        <span className={`${styles.end} ${styles.start}`} style={{ left: 0 }} title={t.origin ?? "Start"} />
        {t.stops
          .filter((s) => s.progress !== null)
          .map((s) => (
            <span key={s.seq} className={stopClass(s, t.progress)} style={{ left: pct(s.progress ?? 0) }} title={stopTitle(s)} />
          ))}
        <span className={styles.end} style={{ left: "100%" }} title={t.destination ?? "Destination"} />
        {!arrived && (
          <span className={styles.truck} style={{ left: pct(t.progress) }} title={`${t.doneKm} km done`}>
            <Truck />
          </span>
        )}
      </div>
      {alert && <div className={styles.alert}>{alert}</div>}
      <div className={styles.foot}>
        <div>
          <span>
            {t.doneKm} of {t.plannedKm} km
          </span>
          {!arrived && <span>{t.remainingKm} km left</span>}
          {t.stops.length > 0 && (
            <span>
              {t.stops.filter((s) => s.status === "DEPARTED" || s.status === "PASSED").length}/{t.stops.length} stops
              {t.stops.some((s) => s.status === "MISSED") ? `, ${t.stops.filter((s) => s.status === "MISSED").length} missed` : ""}
            </span>
          )}
        </div>
        <div>
          {t.promisedEtaAt && <span>promised {clock(t.promisedEtaAt)}</span>}
          <span>pace {t.pace.toFixed(2)}×</span>
          {t.deviations > 0 && (
            <span>
              {t.deviations} deviation{t.deviations === 1 ? "" : "s"}, {t.deviationKm} km extra
            </span>
          )}
          {t.alternates > 0 && <span>{t.alternates} re-planned</span>}
          <span>{t.engine}</span>
        </div>
      </div>
    </>
  );
  const className = `${styles.strip} ${styles[tone] ?? ""} ${selected ? styles.selected : ""}`;
  if (href) {
    return (
      <Link className={className} href={href}>
        {body}
      </Link>
    );
  }
  if (onSelect) {
    return (
      <button type="button" className={className} onClick={onSelect} aria-pressed={selected}>
        {body}
      </button>
    );
  }
  return <div className={className}>{body}</div>;
}

export function EtaStrips({ trips, hrefOf, onSelect, selected }: { trips: Trip[]; hrefOf?: (t: Trip) => string; onSelect?: (t: Trip) => void; selected?: number | null }) {
  return (
    <div className={styles.strips}>
      {trips.map((t) => (
        <EtaStrip
          key={t.planId}
          trip={t}
          href={hrefOf?.(t)}
          onSelect={onSelect ? () => onSelect(t) : undefined}
          selected={selected === t.planId}
        />
      ))}
    </div>
  );
}
