"use client";

import { LineChart } from "@/components/line-chart";
import { Facts, PageHead, Section, ServiceNotice, Stat, StaticStat } from "@/components/ui";
import { formatDistance, formatDuration, formatNumber, formatPercent, formatRate, formatTime } from "@/lib/format";
import { STAGE, ingress, isHealthyGroupState, isKafkaFlow, producer, storage } from "@/lib/pipeline";
import type { EngineOverview, TripCounts, TripView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function OverviewPage() {
  const overview = usePoll<EngineOverview>("/api/engine/overview", 2000);
  const counts = usePoll<TripCounts>("/api/trips/count", 5000);
  const trips = usePoll<TripView[]>("/api/trips?limit=500", 15000);

  const o = overview.data;
  const framesIn = ingress(o);
  const stored = storage(o);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const kafka = isKafkaFlow(o);

  // Medians, not averages: one replayed drive that jumps back to its start can add a 1,000 km "trip".
  const closedTrips = (trips.data ?? []).filter((t) => t.status === "CLOSED");
  const medianDistance = median(closedTrips.map((t) => t.distanceMeters));
  const medianDuration = median(closedTrips.map((t) => t.durationSeconds));
  const topSpeed = trips.data && trips.data.length > 0 ? Math.max(...trips.data.map((t) => t.maxSpeedKmph)) : undefined;

  const stableGroups = o?.consumers.filter((c) => isHealthyGroupState(c.groupState)).length ?? 0;
  const rejectedShare =
    rejected && framesIn && framesIn.totalMessages > 0 ? rejected.totalMessages / framesIn.totalMessages : undefined;

  return (
    <>
      <ServiceNotice error={overview.error} hasData={Boolean(o)} />
      <PageHead
        title="Overview"
        lead="The whole GPS pipeline at a glance: frames coming in, positions stored, vehicles on the live map and trips detected. Updates every two seconds."
        meta={o ? `Updated ${formatTime(o.generatedAt)}` : undefined}
      />

      <div className="stats">
        <Stat
          label="Frames per second"
          value={framesIn?.messagesPerSecond}
          format={formatRate}
          foot={framesIn ? `${formatNumber(framesIn.totalMessages)} since the service started` : "Average of the last 10 seconds"}
        />
        <Stat
          label="Positions stored"
          value={o && o.storedPositions >= 0 ? o.storedPositions : undefined}
          foot="Rows in gps_position"
        />
        <Stat
          label="Vehicles on the live map"
          value={o?.vehiclesWithLivePosition}
          foot={o?.redisProblem ? <span className="bad">Redis is not answering</span> : "Latest position kept in Redis"}
        />
        <Stat
          label="Trips in progress"
          value={counts.data?.openTrips}
          foot={counts.data ? `${formatNumber(counts.data.closedTrips)} completed` : "Detected from movement"}
        />
      </div>

      <Section
        title="Throughput"
        description="Messages per second over the last minute, each point averaged over 6 seconds. Move the pointer over the chart to read any moment."
      >
        <LineChart
          unit="per second"
          averageSeconds={6}
          series={[
            { name: "Frames in", values: framesIn?.lastMinutePerSecond ?? [], color: "var(--chart-1)" },
            { name: "Stored", values: stored?.lastMinutePerSecond ?? [], color: "var(--chart-2)" },
          ]}
        />
      </Section>

      <div className="columns">
        <Section title="Systems" description="What the service can reach right now.">
          <Facts
            rows={[
              ["Processing flow", o ? (kafka ? "Kafka" : "Direct, without Kafka") : "–"],
              [
                "Kafka",
                !o ? "–" : !kafka ? <span className="faint">Not used</span> : o.kafkaProblem ? (
                  <span className="bad" title={o.kafkaProblem}>Not answering</span>
                ) : (
                  <span className="good">Connected, {o.topics.length} topics</span>
                ),
              ],
              [
                "Consumer groups",
                !o || !kafka ? "–" : (
                  <span className={stableGroups === o.consumers.length ? "good" : "warn"}>
                    {stableGroups} of {o.consumers.length} stable
                  </span>
                ),
              ],
              [
                "Redis",
                !o ? "–" : o.redisProblem ? (
                  <span className="bad" title={o.redisProblem}>Not answering</span>
                ) : (
                  <span className="good">Connected</span>
                ),
              ],
              [
                "PostgreSQL",
                !o ? "–" : o.storedPositions < 0 ? <span className="bad">Not answering</span> : <span className="good">Connected</span>,
              ],
              [
                "Rejected frames",
                rejected ? `${formatNumber(rejected.totalMessages)}, ${formatPercent(rejectedShare ?? 0)} of frames` : "–",
              ],
            ]}
          />
        </Section>

        <Section title="Trips and stops" description="Found from each vehicle's movement over the last 24 hours.">
          <Facts
            rows={[
              ["Trips in progress", formatNumber(counts.data?.openTrips)],
              ["Trips completed", formatNumber(counts.data?.closedTrips)],
              ["Vehicles stopped now", formatNumber(counts.data?.openStops)],
              ["Stops recorded", formatNumber(counts.data?.closedStops)],
              ["Typical trip distance", closedTrips.length ? formatDistance(medianDistance) : "–"],
              ["Typical trip duration", closedTrips.length ? formatDuration(medianDuration) : "–"],
              ["Highest speed reported", topSpeed !== undefined ? `${formatNumber(topSpeed)} km/h` : "–"],
            ]}
          />
        </Section>
      </div>

      <Section
        title="Violation and anomaly breakdown"
        description="Speed checked twice (device speedometer against distance over time), stoppages judged with traffic context, and GPS noise smoothed."
        demo
      >
        <div className="stats small">
          <StaticStat label="Over-speed confirmed by both checks" value="94.8" unit="%" foot="52 tamper attempts set aside" />
          <StaticStat label="Context confidence" value="96.4" unit="%" foot="789 genuine congestions" />
          <StaticStat label="GPS noise after smoothing" value="-38.2" unit="%" foot="Positions within 1.2 m" />
          <StaticStat label="Trip completion rate" value="98.1" unit="%" foot="Route deviation 2.4 min on average" />
        </div>
      </Section>
    </>
  );
}

function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}
