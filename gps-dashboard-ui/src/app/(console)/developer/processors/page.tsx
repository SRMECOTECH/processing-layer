"use client";

import { Fragment, useDeferredValue, useState } from "react";
import { Empty, Facts, PageHead, Reveal, Section, ServiceNotice } from "@/components/ui";
import {
  formatAgo,
  formatCoordinate,
  formatDateTime,
  formatNumber,
  formatRate,
  formatTime,
  humanize,
} from "@/lib/format";
import { STAGE, isHealthyGroupState, isKafkaFlow, stageName } from "@/lib/pipeline";
import type { ConsumerView, DeviceLoadView, EngineOverview } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const VEHICLES_SHOWN = 40;

export default function ProcessorsPage() {
  const overview = usePoll<EngineOverview>("/api/engine/overview", 2000);
  const o = overview.data;
  const kafka = isKafkaFlow(o);

  return (
    <>
      <ServiceNotice error={overview.error} hasData={Boolean(o)} />
      <PageHead
        title="Stream processors"
        lead="Every producer and consumer in the pipeline, how much each one handles, and how far behind it is. Select a consumer to see its partitions."
        meta={o ? `${kafka ? "Kafka" : "Direct"} flow, updated ${formatTime(o.generatedAt)}` : undefined}
      />

      <Section title="Producers" description="Write messages into the three Kafka topics.">
        {!o ? (
          <Empty>Waiting for the service.</Empty>
        ) : !kafka ? (
          <Empty>The direct flow does not use producers. Frames go straight from the simulator to processing.</Empty>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Producer</th>
                  <th>Topic</th>
                  <th className="right">Per second</th>
                  <th className="right">Total</th>
                  <th className="right">Failures</th>
                  <th className="right">Last message</th>
                </tr>
              </thead>
              <tbody>
                {o.producers.map((p) => (
                  <tr key={p.stage}>
                    <td>{stageName(p.stage)}</td>
                    <td className="mono muted">{p.topic}</td>
                    <td className="right">{formatRate(p.messagesPerSecond)}</td>
                    <td className="right">{formatNumber(p.totalMessages)}</td>
                    <td className={`right${p.failures > 0 ? " bad" : " faint"}`}>{formatNumber(p.failures)}</td>
                    <td className="right muted">{formatAgo(p.lastMessageAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Consumers overview={o} />

      <Vehicles />

      <div className="columns">
        <Section title="Speed violation engine" description="Speed from the device compared with speed worked out from distance and time." demo>
          <Facts
            rows={[
              ["Readings checked, last minute", "2,892,400"],
              ["Over the limit for 60 s or more", <span className="bad" key="v">14 active, 2 critical</span>],
              ["Speedometer against calculated speed", "0.42 km/h apart"],
              ["Suspicious position jumps", "8 detected"],
            ]}
          />
        </Section>
        <Section title="Stoppage and ignition" description="Separates real stops from traffic jams and planned engine-off at depots." demo>
          <Facts
            rows={[
              ["Alarm after standing still", "180 s"],
              ["False stoppage alarms suppressed", "99.4%"],
              ["Alarms parked today", "1,412 vehicles"],
              ["Traffic context", "Heavy congestion on Route 101"],
            ]}
          />
        </Section>
      </div>

      <Section title="Geofences and trajectory" description="Nested zones, corridors and smoothed GPS tracks." demo>
        <Facts
          rows={[
            ["Inside the outer industrial zone", "384 vehicles"],
            ["At loading dock 4B", "18 vehicles, 14 min average dwell"],
            ["Corridor tolerance", "15 m either side of the route"],
            ["Track smoothing fit", "0.994"],
          ]}
        />
      </Section>
    </>
  );
}

function Consumers({ overview }: { overview: EngineOverview | undefined }) {
  const [open, setOpen] = useState<string | null>(null);
  const kafka = isKafkaFlow(overview);

  return (
    <Section
      title="Consumers"
      description="Each consumer group reads a whole topic for one purpose and keeps its own place in it. Lag is how many messages it still has to read."
    >
      {!overview ? (
        <Empty>Waiting for the service.</Empty>
      ) : !kafka ? (
        <Facts
          rows={[
            ["Direct flow, per second", formatRate(overview.directFlow.messagesPerSecond)],
            ["Direct flow, total", formatNumber(overview.directFlow.totalMessages)],
            ["Failures", formatNumber(overview.directFlow.failures)],
            ["Last message", formatAgo(overview.directFlow.lastMessageAt)],
          ]}
        />
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Consumer</th>
                <th>Group</th>
                <th className="right">Per second</th>
                <th className="right">Total</th>
                <th className="right">Lag</th>
                <th className="right">Members</th>
                <th className="right">State</th>
              </tr>
            </thead>
            <tbody>
              {overview.consumers.map((c) => {
                const isOpen = open === c.load.stage;
                return (
                  <Fragment key={c.load.stage}>
                    <tr
                      className={`clickable${isOpen ? " selected" : ""}`}
                      tabIndex={0}
                      aria-expanded={isOpen}
                      onClick={() => setOpen(isOpen ? null : c.load.stage)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          setOpen(isOpen ? null : c.load.stage);
                        }
                      }}
                    >
                      <td>{stageName(c.load.stage)}</td>
                      <td className="mono muted">{c.load.consumerGroup}</td>
                      <td className="right">{formatRate(c.load.messagesPerSecond)}</td>
                      <td className="right">{formatNumber(c.load.totalMessages)}</td>
                      <td className={`right${c.totalLag > 1000 ? " warn" : ""}`}>{formatNumber(c.totalLag)}</td>
                      <td className="right">{c.members.length}</td>
                      <td className={`right ${isHealthyGroupState(c.groupState) ? "good" : "warn"}`}>
                        {humanize(c.groupState).toLowerCase()}
                      </td>
                    </tr>
                    <tr className="detail-row">
                      <td colSpan={7}>
                        <Reveal open={isOpen}>
                          <ConsumerDetail consumer={c} />
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

function ConsumerDetail({ consumer }: { consumer: ConsumerView }) {
  return (
    <div className="detail-grid">
      <div>
        <p className="muted" style={{ marginBottom: 12 }}>{consumer.load.label}</p>
        <Facts
          rows={[
            ["Reads topic", <span className="mono" key="t">{consumer.load.topic}</span>],
            ["Failures", formatNumber(consumer.load.failures)],
            ["Devices seen", formatNumber(consumer.load.devicesSeen)],
            ["Last message", formatAgo(consumer.load.lastMessageAt)],
          ]}
        />
      </div>
      <div>
        {consumer.partitions.length === 0 ? (
          <Empty>Kafka reported no partitions for this group.</Empty>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Partition</th>
                <th className="right">Read up to</th>
                <th className="right">Latest</th>
                <th className="right">Lag</th>
              </tr>
            </thead>
            <tbody>
              {consumer.partitions.map((p) => (
                <tr key={`${p.topic}-${p.partition}`}>
                  <td>{p.partition}</td>
                  <td className="right">{formatNumber(p.committedOffset)}</td>
                  <td className="right">{formatNumber(p.latestOffset)}</td>
                  <td className="right">{formatNumber(p.lag)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function Vehicles() {
  const [query, setQuery] = useState("");
  const [showAll, setShowAll] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const deferredQuery = useDeferredValue(query.trim());

  const devices = usePoll<DeviceLoadView[]>(
    `/api/engine/devices${deferredQuery ? `?imei=${encodeURIComponent(deferredQuery)}` : ""}`,
    5000,
  );
  const list = devices.data ?? [];
  const shown = showAll ? list : list.slice(0, VEHICLES_SHOWN);

  return (
    <Section
      title="Vehicles"
      description="Every device the pipeline has seen, which partition its frames land in, and how many messages each step handled for it. Select one for its live position."
      meta={devices.data ? `${formatNumber(list.length)} devices` : undefined}
    >
      <div className="toolbar">
        <input
          className="input"
          type="search"
          placeholder="Search by IMEI"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Search vehicles by IMEI"
        />
        {list.length > VEHICLES_SHOWN && (
          <button type="button" className="text-button" onClick={() => setShowAll(!showAll)}>
            {showAll ? `Show first ${VEHICLES_SHOWN}` : `Show all ${formatNumber(list.length)}`}
          </button>
        )}
      </div>
      {list.length === 0 ? (
        <Empty>{devices.data ? "No devices match." : "Waiting for the service."}</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>IMEI</th>
                <th className="right">Partition</th>
                <th className="right">Frames in</th>
                <th className="right">Stored</th>
                <th className="right">Live updates</th>
                <th className="right">Trip checks</th>
                <th className="right">Rejected</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((d) => {
                const isOpen = open === d.imei;
                const count = (stage: string) => formatNumber(d.messagesByStage[stage] ?? 0);
                return (
                  <Fragment key={d.imei}>
                    <tr
                      className={`clickable${isOpen ? " selected" : ""}`}
                      tabIndex={0}
                      aria-expanded={isOpen}
                      onClick={() => setOpen(isOpen ? null : d.imei)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault();
                          setOpen(isOpen ? null : d.imei);
                        }
                      }}
                    >
                      <td className="mono">{d.imei}</td>
                      <td className="right">{d.partition}</td>
                      <td className="right">{count(STAGE.rawFrameProducer)}</td>
                      <td className="right">{count(STAGE.positionStorage)}</td>
                      <td className="right">{count(STAGE.liveLocation)}</td>
                      <td className="right">{count(STAGE.tripDetection)}</td>
                      <td className="right">{count(STAGE.rejectedFrameProducer)}</td>
                    </tr>
                    <tr className="detail-row">
                      <td colSpan={7}>
                        <Reveal open={isOpen}>
                          <VehicleDetail imei={d.imei} />
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

function VehicleDetail({ imei }: { imei: string }) {
  const detail = usePoll<DeviceLoadView>(`/api/engine/devices/${encodeURIComponent(imei)}`, 3000);
  const live = detail.data?.livePosition ?? {};

  if (!detail.data) {
    return <p className="faint">{detail.error ?? "Loading"}</p>;
  }
  if (live.problem) {
    return <p className="bad">{live.problem}</p>;
  }
  if (!live.imei) {
    return <p className="faint">No live position in Redis for this device yet.</p>;
  }
  return (
    <div className="detail-grid">
      <Facts
        rows={[
          ["Vehicle", live.vehicleRegistrationNo || <span className="faint" key="v">Not fitted to a known vehicle</span>],
          ["Tenant", live.tenantCode || "–"],
          ["Position", <span className="mono" key="p">{formatCoordinate(live.latitude, live.longitude)}</span>],
        ]}
      />
      <Facts
        rows={[
          ["Speed", `${formatNumber(Number(live.speedKmph))} km/h`],
          ["Movement", live.movementStatus === "MOVING" ? "Moving" : "Stopped"],
          ["Reading taken", `${formatDateTime(live.recordedAt)}, ${formatAgo(live.recordedAt)}`],
        ]}
      />
    </div>
  );
}
