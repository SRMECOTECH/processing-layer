"use client";

import { Fragment, useState } from "react";
import { Empty, Facts, PageHead, Reveal, Section, ServiceNotice, Stat, StaticStat } from "@/components/ui";
import { formatNumber, formatPercent, formatRate, formatTime } from "@/lib/format";
import { STAGE, consumer, ingress, isKafkaFlow, producer, storage } from "@/lib/pipeline";
import type { EngineOverview, TopicView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

// The checks in com.srm.gps.processing.rule.GpsFrameValidationRules, in the order they run.
const VALIDATION_RULES: [string, string][] = [
  ["IMEI", "Must be present"],
  ["Recording time", "Must be present and not more than 5 minutes in the future"],
  ["Latitude and longitude", "Must be present, within -90 to 90 and -180 to 180"],
  ["GPS fix", "A position of exactly 0, 0 means no fix and is refused"],
  ["Speed", "Must be present, between 0 and 150 km/h"],
  ["Duplicates", "The same reading arriving twice is stored once"],
];

export default function IngestionPage() {
  const overview = usePoll<EngineOverview>("/api/engine/overview", 2000);
  const o = overview.data;
  const kafka = isKafkaFlow(o);

  const framesIn = ingress(o);
  const stored = storage(o);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const storageGroup = consumer(o, STAGE.positionStorage);
  const liveGroup = consumer(o, STAGE.liveLocation);
  const rejectedShare =
    rejected && framesIn && framesIn.totalMessages > 0 ? rejected.totalMessages / framesIn.totalMessages : undefined;

  return (
    <>
      <ServiceNotice error={overview.error} hasData={Boolean(o)} />
      <PageHead
        title="Ingestion and storage"
        lead="How frames enter the platform, which ones are refused and why, and where the accepted positions are kept."
        meta={o ? `Updated ${formatTime(o.generatedAt)}` : undefined}
      />

      <div className="stats">
        <Stat label="Frames in per second" value={framesIn?.messagesPerSecond} format={formatRate} foot="Average of the last 10 seconds" />
        <Stat label="Stored per second" value={stored?.messagesPerSecond} format={formatRate} foot="Into PostgreSQL" />
        <Stat
          label="Rejected frames"
          value={rejected?.totalMessages}
          foot={rejectedShare !== undefined ? `${formatPercent(rejectedShare)} of all frames` : "Failed validation"}
        />
        <Stat
          label="Storage lag"
          value={kafka ? storageGroup?.totalLag : undefined}
          foot={kafka ? "Messages still to be stored" : "Not used in the direct flow"}
        />
      </div>

      <Topics overview={o} />

      <Section
        title="Validation gate"
        description="Every frame is checked before anything else happens. A frame that fails one check goes to the rejected-frames topic with the reason, and is logged."
        meta={rejected ? `${formatNumber(rejected.totalMessages)} rejected so far` : undefined}
      >
        <Facts rows={VALIDATION_RULES.map(([check, rule]) => [check, <span className="muted" key={check}>{rule}</span>])} />
      </Section>

      <div className="columns">
        <Section title="PostgreSQL" description="The permanent record. Database gps_tracking.">
          <Facts
            rows={[
              [
                "Status",
                !o ? "–" : o.storedPositions < 0 ? <span className="bad" key="s">Not answering</span> : <span className="good" key="s">Connected</span>,
              ],
              ["Positions stored", o && o.storedPositions >= 0 ? formatNumber(o.storedPositions) : "–"],
              ["Writes per second", formatRate(stored?.messagesPerSecond)],
              ["Write failures", formatNumber(stored?.failures)],
              ["Tables", <span className="mono muted" key="t">gps_position, gps_trip, gps_stop</span>],
            ]}
          />
        </Section>
        <Section title="Redis" description="The live map: one entry per vehicle, overwritten with every newer reading.">
          <Facts
            rows={[
              [
                "Status",
                !o ? "–" : o.redisProblem ? <span className="bad" key="s">Not answering</span> : <span className="good" key="s">Connected</span>,
              ],
              ["Vehicles with a live position", formatNumber(o?.vehiclesWithLivePosition)],
              ["Updates per second", formatRate(kafka ? liveGroup?.load.messagesPerSecond : o?.directFlow.messagesPerSecond)],
              ["Keys", <span className="mono muted" key="k">gps:live:vehicle:{"{imei}"}, gps:live:positions</span>],
            ]}
          />
        </Section>
      </div>

      <div className="columns">
        <Section title="ClickHouse columnar sink" description="Append-only store for history, replay and fleet-wide analytics." demo>
          <Facts
            rows={[
              ["Batch size", "50,000 rows or 2 s"],
              ["Compression", "4.82 times"],
              ["Insert rate", "118 MB/s"],
              ["Retention", "365 days"],
            ]}
          />
        </Section>
        <Section title="Worker buffers and backpressure" description="Memory held by the writers and how close they are to slowing down intake." demo>
          <div className="stats small">
            <StaticStat label="Buffer in use" value="34.2" unit="%" foot="of 32 GB" />
            <StaticStat label="Backpressure" value="0.00" unit="%" foot="Slows intake at 85%" />
            <StaticStat label="Kafka to storage" value="3.8" unit="ms" foot="Average" />
          </div>
        </Section>
      </div>
    </>
  );
}

function Topics({ overview }: { overview: EngineOverview | undefined }) {
  const [open, setOpen] = useState<string | null>(null);
  const kafka = isKafkaFlow(overview);

  return (
    <Section
      title="Topics"
      description="The three Kafka topics. Frames of one device always land in the same partition, so they stay in order. Select a topic for its partitions."
    >
      {!overview ? (
        <Empty>Waiting for the service.</Empty>
      ) : !kafka ? (
        <Empty>The direct flow does not use Kafka topics.</Empty>
      ) : overview.kafkaProblem ? (
        <Empty>
          <span className="bad">{overview.kafkaProblem}</span>
        </Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Topic</th>
                <th className="right">Partitions</th>
                <th className="right">Messages kept</th>
                <th className="right">Ever written</th>
              </tr>
            </thead>
            <tbody>
              {overview.topics.map((t) => {
                const isOpen = open === t.name;
                return (
                  <Fragment key={t.name}>
                    <tr
                      className={`clickable${isOpen ? " selected" : ""}`}
                      tabIndex={0}
                      aria-expanded={isOpen}
                      onClick={() => setOpen(isOpen ? null : t.name)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          setOpen(isOpen ? null : t.name);
                        }
                      }}
                    >
                      <td className="mono">{t.name}</td>
                      <td className="right">{t.partitions.length}</td>
                      <td className="right">{formatNumber(t.messagesKept)}</td>
                      <td className="right">{formatNumber(t.messagesEverWritten)}</td>
                    </tr>
                    <tr className="detail-row">
                      <td colSpan={4}>
                        <Reveal open={isOpen}>
                          <TopicPartitions topic={t} />
                        </Reveal>
                      </td>
                    </tr>
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  );
}

function TopicPartitions({ topic }: { topic: TopicView }) {
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Partition</th>
          <th className="right">Oldest offset kept</th>
          <th className="right">Next offset</th>
          <th className="right">Messages kept</th>
        </tr>
      </thead>
      <tbody>
        {topic.partitions.map((p) => (
          <tr key={p.partition}>
            <td>{p.partition}</td>
            <td className="right">{formatNumber(p.earliestOffset)}</td>
            <td className="right">{formatNumber(p.latestOffset)}</td>
            <td className="right">{formatNumber(p.latestOffset - p.earliestOffset)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
