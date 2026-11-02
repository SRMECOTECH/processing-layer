"use client";

import Link from "next/link";
import { useState } from "react";
import { Waypoints } from "@/components/icons";
import { Callout, Card, Empty, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader } from "@/components/ops/module";
import { EtaStrip } from "@/components/routes/eta-strip";
import { moduleById } from "@/lib/modules";
import { STOP_LABEL, clock, stopTone, type LiveRoutes } from "@/lib/routes";
import { usePoll } from "@/lib/use-poll";

const WAYPOINTS = moduleById("waypoints")!;
type Kind = "ALL" | "MANUAL" | "FENCE";

/** Each trip's stops in order, with what the truck did at each. */
export default function RoutesAndWaypointsPage() {
  const [kind, setKind] = useState<Kind>("ALL");
  const { data, error } = usePoll<LiveRoutes>("/api/routes/live?hours=24", 10_000);
  const trips = (data?.trips ?? []).filter((t) => t.stops.length > 0);
  const stops = trips.flatMap((t) => t.stops);
  const count = (s: string) => stops.filter((x) => x.status === s).length;

  return (
    <div className="stack">
      <ModuleHeader
        module={WAYPOINTS}
        title="Routes & waypoints"
        description="The stops of every planned trip, in the order the road meets them: the waypoints someone planned, and every geofence the planned road crosses. Reached after 2 minutes inside, left with the stay, driven through, or missed once the truck is 2 km past."
      />
      {error && !data && <Callout tone="warn" title="Trips did not load">{error}</Callout>}

      <Stats columns={5}>
        <Tile label="Trips with stops" value={trips.length} big />
        <Tile label="Ahead" value={count("PENDING")} big />
        <Tile label="At a stop now" value={count("ARRIVED") + count("INSIDE")} tone="good" big />
        <Tile label="Visited / driven through" value={count("DEPARTED") + count("PASSED")} big />
        <Tile label="Missed" value={count("MISSED")} tone={count("MISSED") ? "bad" : undefined} note={`${stops.filter((s) => s.outOfOrder).length} out of order`} big />
      </Stats>

      <Card
        icon={Waypoints}
        title="Trips and their stops"
        live={!error}
        actions={
          <Segmented<Kind>
            label="Stops"
            value={kind}
            onChange={setKind}
            options={[
              ["ALL", "All"],
              ["MANUAL", "Planned"],
              ["FENCE", "Geofences"],
            ]}
          />
        }
      >
        {trips.length ? (
          <div className="stack">
            {trips.map((t) => (
              <div key={t.planId} className="stack">
                <EtaStrip trip={t} href={`/alerts/route-deviation/trip?id=${t.planId}`} />
                <div className="data-table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>#</th>
                        <th>Stop</th>
                        <th className="right">km</th>
                        <th>Planned</th>
                        <th>ETA / arrived</th>
                        <th>Left</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {t.stops
                        .filter((s) => kind === "ALL" || s.kind === kind)
                        .map((s) => (
                          <tr key={s.seq}>
                            <td className="mono">{s.seq}</td>
                            <td>
                              {s.name} <span className="faint">{s.kind === "MANUAL" ? "planned" : "geofence"}</span>
                            </td>
                            <td className="right mono">{s.km ?? "–"}</td>
                            <td className="mono">{clock(s.plannedAt)}</td>
                            <td className="mono">{s.arrivedAt ? clock(s.arrivedAt) : s.etaAt ? `≈ ${clock(s.etaAt)}` : "–"}</td>
                            <td className="mono">{clock(s.departedAt)}</td>
                            <td>
                              <Tag tone={stopTone(s.status)}>{STOP_LABEL[s.status]}</Tag> {s.outOfOrder && <Tag tone="warn">OUT OF ORDER</Tag>}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <Empty>
            No trip with stops yet. Stops come with route plans:{" "}
            <Link href="/alerts/route-deviation/plan">plan a route with stops</Link>, or a shipment whose road crosses geofences.
          </Empty>
        )}
      </Card>

      <HowItWorks module={WAYPOINTS} />
    </div>
  );
}
