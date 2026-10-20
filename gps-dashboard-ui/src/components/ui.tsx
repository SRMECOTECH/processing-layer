"use client";

import { useEffect, useState, type ReactNode } from "react";
import { AnimatedNumber } from "./animated-number";
import { formatNumber } from "@/lib/format";

export function PageHead({ title, lead, meta }: { title: string; lead: string; meta?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        <h1 className="page-title">{title}</h1>
        <p className="page-lead">{lead}</p>
      </div>
      {meta && <div className="page-meta">{meta}</div>}
    </header>
  );
}

type SectionProps = {
  title: string;
  description?: string;
  meta?: ReactNode;
  /** Marks numbers the backend does not produce yet. */
  demo?: boolean;
  children: ReactNode;
};

export function Section({ title, description, meta, demo, children }: SectionProps) {
  return (
    <section className="section">
      <div className="section-head">
        <div>
          <h2 className="section-title">{title}</h2>
          {description && <p className="section-description">{description}</p>}
        </div>
        {(meta || demo) && (
          <div className="section-meta">
            {demo ? <span className="demo-note">Demo data, not produced by the service yet</span> : meta}
          </div>
        )}
      </div>
      {children}
    </section>
  );
}

type StatProps = {
  label: string;
  value: number | null | undefined;
  format?: (value: number) => string;
  unit?: string;
  foot?: ReactNode;
  tone?: "good" | "bad" | "warn";
};

export function Stat({ label, value, format = formatNumber, unit, foot, tone }: StatProps) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className={`stat-value${tone ? ` ${tone}` : ""}`}>
        <AnimatedNumber value={value} format={format} />
        {unit && <span className="stat-unit">{unit}</span>}
      </div>
      {foot && <div className="stat-foot">{foot}</div>}
    </div>
  );
}

/** A static stat for demo panels: the value is plain text. */
export function StaticStat({ label, value, unit, foot }: { label: string; value: string; unit?: string; foot?: ReactNode }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">
        {value}
        {unit && <span className="stat-unit">{unit}</span>}
      </div>
      {foot && <div className="stat-foot">{foot}</div>}
    </div>
  );
}

export function Facts({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="facts">
      {rows.map(([label, value], index) => (
        <div className="fact" key={index}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Content that opens and closes with a smooth height change; it stays mounted until the close has finished. */
export function Reveal({ open, children }: { open: boolean; children: ReactNode }) {
  const [mounted, setMounted] = useState(open);

  useEffect(() => {
    if (open) {
      setMounted(true);
      return;
    }
    const timer = setTimeout(() => setMounted(false), 360);
    return () => clearTimeout(timer);
  }, [open]);

  return (
    <div className={`reveal${open ? " open" : ""}`} aria-hidden={!open}>
      <div>{open || mounted ? <div className="reveal-body">{children}</div> : null}</div>
    </div>
  );
}

/** Shown at the top of a page when the service does not answer. */
export function ServiceNotice({ error, hasData }: { error: string | undefined; hasData: boolean }) {
  if (!error) return null;
  return (
    <div className="notice" role="status">
      <div className="notice-title">{hasData ? "Lost contact with the GPS service" : "The GPS service is not answering"}</div>
      <p className="notice-text">
        {error}. {hasData ? "Showing the last numbers received. " : ""}Retrying automatically. Start the service with
        start-gps-service.ps1 or docker compose up.
      </p>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="table-empty">{children}</p>;
}
