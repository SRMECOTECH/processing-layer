"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { LucideIcon } from "lucide-react";
import { useState, type ReactNode } from "react";
import { AnimatedNumber } from "@/components/animated-number";
import { alertsFrom, seriousCount } from "@/lib/alerts";
import { formatNumber } from "@/lib/format";
import { QUICK_FILTERS } from "@/lib/nav";
import { useOverview } from "@/lib/overview-context";

export type Tone = "accent" | "good" | "bad" | "warn" | "neutral" | "ink";

export function Tag({ tone = "accent", children, mono }: { tone?: Tone; children: ReactNode; mono?: boolean }) {
  return <span className={`tag ${tone}${mono ? " mono" : ""}`}>{children}</span>;
}

/** "Live" for cards fed by the service, "Demo data" for cards the backend does not produce yet. */
export function Source({ live }: { live: boolean }) {
  return live ? (
    <span className="source live" title="Numbers come from the GPS service">
      <i />
      Live
    </span>
  ) : (
    <span className="source demo" title="Example numbers: the service does not produce this yet">
      Demo data
    </span>
  );
}

export function PageTitle({ title, subtitle, right }: { title: string; subtitle?: string; right?: ReactNode }) {
  return (
    <div className="ops-head">
      <div>
        <h1 className="ops-title">{title}</h1>
        {subtitle && <p className="ops-subtitle">{subtitle}</p>}
      </div>
      {right && <div className="ops-head-right">{right}</div>}
    </div>
  );
}

/** Quick filters above every operational page (they jump to the owning sub-page). */
export function QuickFilters() {
  const pathname = usePathname();
  const { data } = useOverview();
  const critical = seriousCount(alertsFrom(data));
  return (
    <div className="chips" role="navigation" aria-label="Quick filters">
      {QUICK_FILTERS.map((f) => (
        <Link key={f.href} href={f.href} className="chip" aria-current={pathname === f.href ? "page" : undefined}>
          {f.label}
        </Link>
      ))}
      <Link href="/rules/dispatch" className="chip danger" aria-current={pathname === "/rules/dispatch" ? "page" : undefined}>
        <i />
        Critical Alerts{critical > 0 ? ` (${critical})` : ""}
      </Link>
    </div>
  );
}

type CardProps = {
  icon?: LucideIcon;
  title: string;
  subtitle?: ReactNode;
  monoSubtitle?: boolean;
  live?: boolean;
  badges?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
};

export function Card({ icon: Icon, title, subtitle, monoSubtitle, live, badges, actions, children, className }: CardProps) {
  return (
    <section className={`card${className ? ` ${className}` : ""}`}>
      <div className="card-head">
        <div className="card-head-main">
          {Icon && (
            <span className="card-icon">
              <Icon size={19} />
            </span>
          )}
          <div className="card-head-text">
            <div className="card-title-row">
              <h2 className="card-title">{title}</h2>
              {badges}
            </div>
            {subtitle && <p className={`card-subtitle${monoSubtitle ? " mono" : ""}`}>{subtitle}</p>}
          </div>
        </div>
        <div className="card-head-side">
          {live !== undefined && <Source live={live} />}
          {actions}
        </div>
      </div>
      {children}
    </section>
  );
}

type TileProps = {
  label: string;
  icon?: LucideIcon;
  value: number | string | null | undefined;
  format?: (value: number) => string;
  unit?: string;
  note?: ReactNode;
  foot?: ReactNode;
  progress?: number;
  tone?: Tone;
  big?: boolean;
};

/** A stat block on a light tile: label, a large mono number, an optional note and progress line. */
export function Tile({ label, icon: Icon, value, format = formatNumber, unit, note, foot, progress, tone, big }: TileProps) {
  return (
    <div className={`tile${big ? " big" : ""}`}>
      <div className="tile-label">
        <span>{label}</span>
        {Icon && <Icon size={15} className="tile-icon" />}
      </div>
      <div className="tile-value-row">
        <span className={`tile-value${tone ? ` ${tone}` : ""}`}>
          {typeof value === "number" || value === null || value === undefined ? (
            <AnimatedNumber value={value} format={format} />
          ) : (
            value
          )}
        </span>
        {unit && <span className="tile-unit">{unit}</span>}
        {note && <span className="tile-note">{note}</span>}
      </div>
      {progress !== undefined && <Progress value={progress} tone={tone === "bad" ? "bad" : undefined} />}
      {foot && <div className="tile-foot">{foot}</div>}
    </div>
  );
}

export function Progress({ value, tone }: { value: number; tone?: "bad" | "good" | "warn" }) {
  const width = `${Math.max(0, Math.min(1, value)) * 100}%`;
  return (
    <div className={`progress${tone ? ` ${tone}` : ""}`}>
      <span style={{ width }} />
    </div>
  );
}

export function KeyValues({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="kv">
      {rows.map(([k, v], i) => (
        <div className="kv-row" key={i}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: [T, string][];
  value: T;
  onChange: (value: T) => void;
  label: string;
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map(([v, text]) => (
        <button key={v} type="button" aria-pressed={v === value} onClick={() => onChange(v)}>
          {text}
        </button>
      ))}
    </div>
  );
}

/** Shows a short confirmation instead of doing anything: for buttons on demo cards. */
export function DemoButton({ children, primary, icon: Icon }: { children: ReactNode; primary?: boolean; icon?: LucideIcon }) {
  const [clicked, setClicked] = useState(false);
  return (
    <button
      type="button"
      className={`btn${primary ? " primary" : " soft"}`}
      onClick={() => {
        setClicked(true);
        setTimeout(() => setClicked(false), 1800);
      }}
    >
      {Icon && <Icon size={15} />}
      {clicked ? "Not connected yet" : children}
    </button>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

/** Banner shown on operational pages when the service does not answer. */
export function Offline() {
  const { data, error } = useOverview();
  if (!error) return null;
  return (
    <div className="offline" role="status">
      <strong>{data ? "Lost contact with the GPS service." : "The GPS service is not answering."}</strong>{" "}
      {error}. {data ? "Showing the last numbers received. " : ""}Retrying automatically.
    </div>
  );
}
