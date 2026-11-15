"use client";

import Link from "next/link";
import { Card, KeyValues, Tag, type Tone } from "@/components/ops/kit";
import { STATUS_LABEL, type RoadModule, type Status } from "@/lib/road-intelligence";

export const STATUS_TONE: Record<Status, Tone> = { live: "good", partial: "warn", planned: "neutral" };

export function StatusTag({ status }: { status: Status }) {
  return <Tag tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Tag>;
}

/** One sub-module of Road Intelligence: its features with their status, and what comes from Valhalla versus what we build. */
export function ModuleDetail({ m }: { m: RoadModule }) {
  return (
    <>
      <Card title={`${m.letter}. ${m.title}`} subtitle={m.summary} badges={<StatusTag status={m.status} />}>
        <ul className="plain-list">
          {m.features.map((f) => (
            <li key={f.text} className="toolbar-row">
              <StatusTag status={f.status} /> <span>{f.text}</span>
            </li>
          ))}
        </ul>
      </Card>
      <Card title="Where the data comes from">
        <KeyValues
          rows={[
            ["From Valhalla", m.valhalla],
            ["What we build", m.build],
            ...(m.caveat ? ([["Watch out", m.caveat]] as [string, string][]) : []),
            ["Phase", m.phase === 1 ? "Phase 1 (MVP)" : "Phase 2 (advanced)"],
          ]}
        />
      </Card>
    </>
  );
}

/** A compact tile for the overview grid. */
export function ModuleTile({ m }: { m: RoadModule }) {
  const built = m.features.filter((f) => f.status === "live").length;
  return (
    <Link href={m.href} className="tile linked">
      <div className="tile-label">
        <span>
          {m.letter}. {m.title}
        </span>
        <StatusTag status={m.status} />
      </div>
      <p className="muted">{m.summary}</p>
      <div className="tile-foot">
        {built} of {m.features.length} features built · phase {m.phase}
      </div>
    </Link>
  );
}
