"use client";

import { useState } from "react";
import { Route } from "@/components/icons";
import { Callout, Card, Empty, PageHeader, Segmented, Stats, Tile } from "@/components/ops/kit";
import { EtaStrips } from "@/components/routes/eta-strip";
import { TripView } from "@/components/routes/trip-view";
import { minutesText, type LiveRoutes, type Trip, type TripDetail } from "@/lib/routes";
import { usePoll } from "@/lib/use-poll";

type Show = "ROAD" | "ATTENTION" | "ARRIVED";

const match = (t: Trip, show: Show) =>
  show === "ARRIVED"
    ? t.routeStatus === "ARRIVED"
    : show === "ATTENTION"
      ? t.routeStatus === "OFF_ROUTE" || t.wrongWay || (t.lateMinutes ?? 0) >= 30 || (t.noSignal && t.status === "ACTIVE")
      : t.status === "ACTIVE";

/**
 * Live ETA in a transporter's or consigner's portal: the service cuts the trips to the user's own trucks
 * (GET /api/portal/routes/live); a trip opens below the list with its map.
 */
export function PortalEta({ description }: { description: string }) {
  const [show, setShow] = useState<Show>("ROAD");
  const [selected, setSelected] = useState<number | null>(null);
  const { data, error } = usePoll<LiveRoutes>("/api/portal/routes/live?hours=24", 10_000);
  const { data: trip } = usePoll<TripDetail>(selected ? `/api/portal/routes/${selected}` : null, 15_000);
  const trips = (data?.trips ?? []).filter((t) => match(t, show));
  const c = data?.counts;

  return (
    <div className="stack">
      <PageHeader description={description} />
      {error && !data && <Callout tone="warn" title="Trips did not load">{error}</Callout>}
      <Stats columns={4}>
        <Tile label="On the road" value={(c?.ON_ROUTE ?? 0) + (c?.SUSPECT ?? 0) + (c?.OFF_ROUTE ?? 0)} big />
        <Tile label="Off route or wrong way" value={(c?.OFF_ROUTE ?? 0) + (c?.WRONG_WAY ?? 0)} tone={c?.OFF_ROUTE || c?.WRONG_WAY ? "bad" : undefined} big />
        <Tile label="Late by 30 min +" value={c?.LATE ?? 0} tone={c?.LATE ? "warn" : undefined} note={data?.averageLateMinutes != null ? `average ${data.averageLateMinutes > 0 ? "+" : ""}${minutesText(data.averageLateMinutes)}` : undefined} big />
        <Tile label="Arrived (24 h)" value={c?.ARRIVED ?? 0} tone="good" big />
      </Stats>
      <Card
        icon={Route}
        title="Your trips"
        subtitle="Each truck along its planned road, with the ETA against the one promised when it left. Choose one for its map."
        live={!error}
        actions={
          <Segmented<Show>
            label="Show"
            value={show}
            onChange={setShow}
            options={[
              ["ROAD", "On the road"],
              ["ATTENTION", "Needs attention"],
              ["ARRIVED", "Arrived"],
            ]}
          />
        }
      >
        {trips.length ? (
          <EtaStrips trips={trips} onSelect={(t) => setSelected(selected === t.planId ? null : t.planId)} selected={selected} />
        ) : (
          <Empty>{data?.trips.length ? "No trip matches this filter." : "No planned trips of your trucks in the last 24 hours."}</Empty>
        )}
      </Card>
      {selected && trip && trip.planId === selected && <TripView trip={trip} />}
    </div>
  );
}
