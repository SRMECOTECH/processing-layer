"use client";

import { use } from "react";
import { Callout, Empty, PageHeader } from "@/components/ops/kit";
import { ModuleDetail } from "@/components/roads/module-card";
import { moduleByKey } from "@/lib/road-intelligence";

/** A Road Intelligence sub-module that is not built yet: its plan, what Valhalla gives, and what we must add. */
export default function RoadModulePage({ params }: { params: Promise<{ topic: string }> }) {
  const { topic } = use(params);
  const m = moduleByKey(topic);
  if (!m) {
    return (
      <div className="stack">
        <PageHeader title="Road Intelligence" />
        <Empty>No such part of Road Intelligence.</Empty>
      </div>
    );
  }
  return (
    <div className="stack">
      <PageHeader title={`${m.letter}. ${m.title}`} description={m.summary} />
      {m.status === "planned" && (
        <Callout tone="planned" title="Planned">
          Not built yet. This page lists what it will do and where the data comes from.
        </Callout>
      )}
      <ModuleDetail m={m} />
    </div>
  );
}
