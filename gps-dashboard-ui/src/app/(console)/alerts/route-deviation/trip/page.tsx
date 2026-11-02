"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Callout, Empty } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { TripView } from "@/components/routes/trip-view";
import { moduleById } from "@/lib/modules";
import type { TripDetail } from "@/lib/routes";
import { post, usePoll } from "@/lib/use-poll";

const ROUTE = moduleById("route")!;

/** One trip: the map with the planned road and the real trail, the numbers, the stops and the events. */
export default function TripPage() {
  const [id, setId] = useState<string | null>(null);
  const [problem, setProblem] = useState<string>();
  useEffect(() => {
    setId(new URLSearchParams(window.location.search).get("id"));
  }, []);
  const { data: t, error, refresh } = usePoll<TripDetail>(id ? `/api/routes/${id}` : null, 10_000);

  async function cancel() {
    if (!t || !window.confirm(`Stop following ${t.vehicle} on this route?`)) return;
    try {
      await post(`/api/routes/${t.planId}/cancel`);
      refresh();
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="stack">
      <ModuleHeader
        module={ROUTE}
        title={t ? `${t.vehicle} · ${t.origin ?? "Start"} → ${t.destination ?? "Destination"}` : "Trip"}
        description="The planned road in grey (the first plan dotted after a re-plan), where the truck really drove - green on the route, red off it - with the stops and where deviations began."
        actions={
          <>
            <Link className="btn soft" href="/alerts/route-deviation">
              All trips
            </Link>
            {t?.status === "ACTIVE" && (
              <button type="button" className="btn soft" onClick={cancel}>
                Stop following
              </button>
            )}
          </>
        }
      />
      {problem && <Callout tone="warn">{problem}</Callout>}
      {error && !t && <Callout tone="warn" title="The trip did not load">{error}</Callout>}
      {id === null && <Empty>Open a trip from Live trips & ETA.</Empty>}
      {t && <TripView trip={t} />}
    </div>
  );
}
