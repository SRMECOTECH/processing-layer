"use client";

import Link from "next/link";
import { Activity, ArrowRight, Car, ChartLine, ListChecks, Radio, Route, Siren, Workflow } from "lucide-react";
import { AreaChart } from "@/components/ops/charts";
import { EventFeed } from "@/components/ops/events";
import { Card, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { moduleLoad } from "@/components/ops/module";
import { FleetButton } from "@/components/simulator-control";
import { formatNumber, formatRate } from "@/lib/format";
import { PROCESSORS } from "@/lib/modules";
import { useOverview } from "@/lib/overview-context";
import { ingress, storage } from "@/lib/pipeline";
import type { EventTypeCount, TripCounts, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function FleetOverviewPage() {
  const { data: o } = useOverview();
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const recent = usePoll<VehicleEventView[]>("/api/events?limit=8", 4000);
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const framesIn = ingress(o);
  const stored = storage(o);
  const seen = framesIn?.devicesSeen ?? 0;
  const open = (type: string) => summary.data?.find((s) => s.type === type)?.openNow ?? 0;
  const openTotal = (summary.data ?? []).reduce((sum, s) => sum + s.openNow, 0);

  return (
    <div className="stack">
      <PageHeader
        title="Fleet overview"
        description="The whole platform at a glance: frames arriving from the devices, vehicles reporting, what the processors are doing and the latest events."
        actions={
          <>
            <Link href="/live/map" className="btn soft">
              Open live map
            </Link>
            <FleetButton />
          </>
        }
      />

      <Stats>
        <Tile label="Frames in" icon={Radio} value={framesIn?.messagesPerSecond} format={formatRate} unit="msg/s" big foot={`${formatNumber(framesIn?.totalMessages)} since start`} href="/live/throughput" />
        <Tile
          label="Vehicles reporting"
          icon={Car}
          value={o?.vehiclesWithLivePosition}
          unit={seen ? `/ ${formatNumber(seen)}` : undefined}
          big
          progress={seen ? (o?.vehiclesWithLivePosition ?? 0) / seen : undefined}
          href="/live/vehicles"
        />
        <Tile label="Trips in progress" icon={Route} value={counts.data?.openTrips} big foot={`${formatNumber(counts.data?.openStops)} vehicles standing`} href="/alerts/trips" />
        <Tile
          label="Open events"
          icon={Siren}
          value={summary.data ? openTotal : undefined}
          big
          tone={open("OVERSPEED") > 0 ? "bad" : undefined}
          foot={`${open("OVERSPEED")} over-speed · ${open("STOPPAGE")} stoppages · ${open("IDLING")} idling`}
          href="/alerts"
        />
      </Stats>

      <div className="grid-main-side">
        <Card icon={ChartLine} title="Stream flow" subtitle="Frames in against positions stored, per second over the last minute." live>
          <AreaChart
            series={[
              { name: "Frames in", values: framesIn?.lastMinutePerSecond ?? [], strong: true },
              { name: "Stored", values: stored?.lastMinutePerSecond ?? [] },
            ]}
          />
        </Card>

        <Card
          icon={Workflow}
          title="Processors"
          subtitle="Parallel consumers after Kafka."
          live
          actions={
            <Link href="/processing" className="text-button">
              Pipeline <ArrowRight size={14} />
            </Link>
          }
        >
          <ul className="row-list">
            {PROCESSORS.map((m) => {
              const { rate, lag } = moduleLoad(o, m);
              const Icon = m.icon;
              return (
                <li key={m.id}>
                  <Link href={m.href} className="row-link">
                    <Icon size={16} className="row-icon" />
                    <span className="row-main">{m.name}</span>
                    {m.status === "planned" ? (
                      <Tag tone="neutral">PLANNED</Tag>
                    ) : (
                      <span className="row-side mono">
                        {formatRate(rate)}/s{lag ? <span className="warn"> · {formatNumber(lag)} lag</span> : null}
                      </span>
                    )}
                  </Link>
                </li>
              );
            })}
          </ul>
        </Card>
      </div>

      <div className="grid-main-side">
        <Card
          icon={ListChecks}
          title="Latest events"
          subtitle="From every stream consumer, newest first."
          live
          actions={
            <Link href="/live/events" className="text-button">
              Event stream <ArrowRight size={14} />
            </Link>
          }
        >
          <EventFeed events={recent.data} empty="No events yet. Start the fleet to see over-speed, stoppage and ignition events." />
        </Card>

        <Card icon={Activity} title="Fleet simulator" subtitle={o?.simulator.lastMessage ?? "Replays recorded trips as live devices."} live>
          <dl className="kv">
            <div className="kv-row">
              <dt>State</dt>
              <dd>{o ? <Tag tone={o.simulator.running ? "good" : "neutral"}>{o.simulator.running ? "RUNNING" : "STOPPED"}</Tag> : "–"}</dd>
            </div>
            <div className="kv-row">
              <dt>Vehicles</dt>
              <dd className="mono">{formatNumber(o?.simulator.vehicleCount)}</dd>
            </div>
            <div className="kv-row">
              <dt>Frames sent</dt>
              <dd className="mono">{formatNumber(o?.simulator.framesSent)}</dd>
            </div>
            <div className="kv-row">
              <dt>Round</dt>
              <dd className="mono">{o ? `${o.simulator.round} of ${o.simulator.roundsPerDrive}` : "–"}</dd>
            </div>
          </dl>
          <div className="card-actions">
            <Link href="/live/simulator" className="btn soft">
              Simulator details
            </Link>
          </div>
        </Card>
      </div>
    </div>
  );
}
