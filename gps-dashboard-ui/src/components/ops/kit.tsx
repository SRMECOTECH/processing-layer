"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { IconComponent } from "@/components/icons";
import { ChevronRight, Info } from "@/components/icons";
import { useState, type ReactNode } from "react";
import { AnimatedNumber } from "@/components/animated-number";
import { Words } from "@/components/words";
import { formatNumber } from "@/lib/format";
import { useSections } from "@/lib/viewer";
import { trailFor, type Feed } from "@/lib/nav";
import { useOverview } from "@/lib/overview-context";

export type Tone = "accent" | "good" | "bad" | "warn" | "neutral" | "ink";

export function Tag({ tone = "accent", children, mono }: { tone?: Tone; children: ReactNode; mono?: boolean }) {
  return <span className={`tag ${tone}${mono ? " mono" : ""}`}>{children}</span>;
}

/** "Live" for cards fed by the service, "Example data" for cards the backend does not produce yet. */
export function Source({ live }: { live: boolean }) {
  return live ? (
    <span className="source live" title="Numbers come from the GPS service">
      <i />
      Live
    </span>
  ) : (
    <span className="source demo" title="Example numbers: the service does not produce this yet">
      Example data
    </span>
  );
}

/** Built, part-built or planned: shown next to a module's title. */
export function FeedTag({ feed }: { feed: Feed }) {
  if (feed === "live") return <Tag tone="good">LIVE</Tag>;
  if (feed === "partial") return <Tag tone="warn">PARTLY BUILT</Tag>;
  return <Tag tone="neutral">PLANNED</Tag>;
}

type HeaderProps = {
  /** Defaults to the page's name in the menu. */
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  badges?: ReactNode;
  /** Small facts under the description: consumer group, topic, Redis keys... */
  meta?: [string, ReactNode][];
};

/** Breadcrumb from the menu, title, description, actions on the right and a row of facts. */
export function PageHeader({ title, description, actions, badges, meta }: HeaderProps) {
  const pathname = usePathname();
  const trail = trailFor(pathname, useSections());
  const crumbs: [string, string | undefined][] = [[trail.section.label, trail.section.href]];
  if (trail.group) crumbs.push([trail.group.title, undefined]);
  if (trail.leaf && trail.item) crumbs.push([trail.item.label, trail.item.href]);

  return (
    <header className="hdr">
      <nav className="crumbs" aria-label="Breadcrumb">
        {crumbs.map(([label, href], i) => (
          <span key={label} className="crumb">
            {i > 0 && <ChevronRight size={13} className="crumb-sep" />}
            {href ? <Link href={href}>{label}</Link> : <span>{label}</span>}
          </span>
        ))}
      </nav>
      <div className="hdr-row">
        <div className="hdr-main">
          <div className="hdr-title-row">
            <h1 className="hdr-title">
              <Words text={title ?? trail.leaf?.label ?? trail.item?.label ?? trail.section.label} />
            </h1>
            {badges}
          </div>
          {description && <p className="hdr-desc">{description}</p>}
        </div>
        {actions && <div className="hdr-actions">{actions}</div>}
      </div>
      {meta && meta.length > 0 && (
        <dl className="hdr-meta">
          {meta.map(([label, value]) => (
            <div key={label} className="hdr-meta-item">
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      )}
    </header>
  );
}

/** Older name, still used by the Developer pages. */
export function PageTitle({ title, subtitle, right }: { title: string; subtitle?: string; right?: ReactNode }) {
  return <PageHeader title={title} description={subtitle} actions={right} />;
}

type CardProps = {
  icon?: IconComponent;
  title: string;
  subtitle?: ReactNode;
  monoSubtitle?: boolean;
  live?: boolean;
  badges?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  /** No padding around the body: for tables that run edge to edge. */
  flush?: boolean;
};

export function Card({ icon: Icon, title, subtitle, monoSubtitle, live, badges, actions, children, className, flush }: CardProps) {
  return (
    <section className={`card${flush ? " flush" : ""}${className ? ` ${className}` : ""}`}>
      <div className="card-head">
        <div className="card-head-main">
          {Icon && (
            <span className="card-icon">
              <Icon size={17} />
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
      <div className="card-body">{children}</div>
    </section>
  );
}

type TileProps = {
  label: string;
  icon?: IconComponent;
  value: number | string | null | undefined;
  format?: (value: number) => string;
  unit?: string;
  note?: ReactNode;
  foot?: ReactNode;
  progress?: number;
  tone?: Tone;
  big?: boolean;
  href?: string;
};

/** A number with its label: large, calm, with an optional note, progress line and link. */
export function Tile({ label, icon: Icon, value, format = formatNumber, unit, note, foot, progress, tone, big, href }: TileProps) {
  const body = (
    <>
      <div className="tile-label">
        <span>{label}</span>
        {Icon && <Icon size={15} className="tile-icon" />}
      </div>
      <div className="tile-value-row">
        <span className={`tile-value${tone ? ` ${tone}` : ""}`}>
          {typeof value === "number" || value === null || value === undefined ? <AnimatedNumber value={value} format={format} /> : value}
        </span>
        {unit && <span className="tile-unit">{unit}</span>}
        {note && <span className="tile-note">{note}</span>}
      </div>
      {progress !== undefined && <Progress value={progress} tone={tone === "bad" ? "bad" : tone === "warn" ? "warn" : undefined} />}
      {foot && <div className="tile-foot">{foot}</div>}
    </>
  );
  const className = `tile${big ? " big" : ""}${href ? " linked" : ""}`;
  return href ? (
    <Link href={href} className={className}>
      {body}
    </Link>
  ) : (
    <div className={className}>{body}</div>
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

/** Shows a short confirmation instead of doing anything: for buttons on cards with example data. */
export function DemoButton({ children, primary, icon: Icon }: { children: ReactNode; primary?: boolean; icon?: IconComponent }) {
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

/** A calm note inside a page: explanation, caveat or what is planned. */
export function Callout({ children, tone = "info", title }: { children: ReactNode; tone?: "info" | "warn" | "planned"; title?: string }) {
  return (
    <div className={`callout ${tone}`}>
      <Info size={16} className="callout-icon" />
      <div>
        {title && <div className="callout-title">{title}</div>}
        <div className="callout-text">{children}</div>
      </div>
    </div>
  );
}

/** Banner shown on operational pages when the service does not answer. */
export function Offline() {
  const { data, error } = useOverview();
  if (!error) return null;
  return (
    <div className="offline" role="status">
      <span className="status-dot bad" />
      <span>
        <strong>{data ? "Lost contact with the GPS service." : "The GPS service is not answering."}</strong>{" "}
        {data ? "Showing the last numbers received. " : "Live cards stay empty until it is back; planned modules show example data. "}
        Retrying automatically.
      </span>
    </div>
  );
}

/** A grid of tiles; 2 to 5 across depending on the screen. */
export function Stats({ children, columns = 4 }: { children: ReactNode; columns?: 2 | 3 | 4 | 5 }) {
  return <div className={`stats-row cols-${columns}`}>{children}</div>;
}

/** Short mono code-like label (Redis key, topic, group). */
export function Code({ children }: { children: ReactNode }) {
  return <code className="code-chip">{children}</code>;
}

/** An on/off switch. */
export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (checked: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className="switch"
      disabled={disabled}
      onClick={(e) => {
        e.preventDefault();
        onChange(!checked);
      }}
    >
      <i />
    </button>
  );
}
