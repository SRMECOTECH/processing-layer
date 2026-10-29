"use client";

import Link from "next/link";
import { GitFork, OctagonAlert } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Card, Stats, Tile } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader } from "@/components/ops/module";
import { moduleById } from "@/lib/modules";
import type { EventTypeCount, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const STOPPAGE = moduleById("stoppage")!;

export default function LiveStoppagesPage() {
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const open = usePoll<VehicleEventView[]>("/api/events?type=STOPPAGE,IDLING&status=OPEN&limit=40", 4000);
  const count = (type: string) => summary.data?.find((s) => s.type === type);
  const n = (type: string, f: "openNow" | "last24Hours") => count(type)?.[f] ?? (summary.data ? 0 : undefined);

  return (
    <div className="stack">
      <ModuleHeader module={STOPPAGE} title="Stoppage & ignition · live stoppages" />

      <Stats>
        <Tile label="Stoppages now" value={n("STOPPAGE", "openNow")} tone={(n("STOPPAGE", "openNow") ?? 0) > 0 ? "warn" : undefined} big foot={`${n("STOPPAGE", "last24Hours") ?? "–"} in 24 hours · engine on`} />
        <Tile label="Idling now" value={n("IDLING", "openNow")} big foot="engine on, standing over 5 min" href="/processing/stoppage/ignition-on" />
        <Tile label="Parked now" value={n("PARKED", "openNow")} big foot="engine off · neutralised, no alarm" href="/processing/stoppage/ignition-off" />
        <Tile label="Ignition changes (24 h)" value={summary.data ? (n("IGNITION_ON", "last24Hours") ?? 0) + (n("IGNITION_OFF", "last24Hours") ?? 0) : undefined} big foot="device wire or virtual" />
      </Stats>

      <Card icon={GitFork} title="What happens when a vehicle stands still" subtitle="Speed is worked out from distance travelled over time between pings, then the ignition decides the path.">
        <div className="decision">
          <div className="decision-node start">
            <div className="decision-title">Reading</div>
            <div className="decision-text">speed = distance ÷ time since the last ping</div>
          </div>
          <div className="decision-arrow">under 3 km/h, within 100 m, for 3 min</div>
          <div className="decision-split">
            <Link href="/processing/stoppage/ignition-on" className="decision-node on">
              <div className="decision-title">Ignition ON</div>
              <div className="decision-text">Follow the rules: stoppage after the threshold, idling after 5 min. Agents check traffic and weather.</div>
              <div className="decision-out">→ STOPPAGE · IDLING</div>
            </Link>
            <Link href="/processing/stoppage/ignition-off" className="decision-node off">
              <div className="decision-title">Ignition OFF</div>
              <div className="decision-text">Parked: no point raising a stoppage. The points are neutralised.</div>
              <div className="decision-out">→ PARKED (no alarm)</div>
            </Link>
          </div>
        </div>
      </Card>

      <Card icon={OctagonAlert} title="Open stoppages and idling" subtitle="Engine on and standing, right now." live flush>
        <EventTable events={open.data} empty="No vehicle is stopped with the engine on." />
      </Card>

      <HowItWorks module={STOPPAGE} />
    </div>
  );
}
