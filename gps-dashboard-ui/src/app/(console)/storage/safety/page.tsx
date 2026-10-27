"use client";

import Link from "next/link";
import { Archive, Database, HardDrive, Network, ShieldCheck, Workflow } from "lucide-react";
import { Callout, Card, Code, Empty, KeyValues, PageHeader, Stats, Tag, Tile, type Tone } from "@/components/ops/kit";
import { formatAgo, formatBytes, formatNumber, formatRetention } from "@/lib/format";
import type { DataSafetyReport, SafetyStatus } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const TONE: Record<SafetyStatus, Tone> = { OK: "good", WARN: "warn", FAIL: "bad" };

export default function DataSafetyPage() {
  const { data: r, error } = usePoll<DataSafetyReport>("/api/data-safety", 5000);
  const lost = r?.groups.reduce((n, g) => n + g.lostRecords, 0) ?? 0;
  const copies = r && r.topics.length ? Math.min(...r.topics.map((t) => t.replicationFactor)) : undefined;
  const inSync = r && r.topics.length ? Math.min(...r.topics.map((t) => t.minInsyncReplicas ?? 1)) : undefined;
  const failing = r?.checks.filter((c) => c.status !== "OK") ?? [];

  return (
    <div className="stack">
      <PageHeader
        description="Every record the platform receives is kept somewhere until it is safely handled: in the ingest journal on disk until Kafka confirms it, in Kafka for a month, and - if a consumer cannot handle it - in the dead-letter archive in PostgreSQL for good. This page checks every one of those promises against the live system."
        badges={r ? <Tag tone={TONE[r.verdict]}>{r.verdict === "OK" ? "ALL CHECKS PASS" : r.verdict === "WARN" ? `${failing.length} WARNING${failing.length === 1 ? "" : "S"}` : "DATA AT RISK"}</Tag> : undefined}
        meta={[
          ["Kept in Kafka", formatRetention(r?.retentionTargetMs)],
          ["Copies per record", copies === undefined ? "–" : `${copies} · ${inSync} before ok`],
          ["Journal", <Code key="j">{r?.journal?.directory ?? "–"}</Code>],
          ["Dead letters", <Code key="d">gps.dead-letters → dead_letter</Code>],
        ]}
      />

      {!r ? (
        <Empty>{error ?? "Waiting for the service."}</Empty>
      ) : (
        <>
          <Stats columns={5}>
            <Tile label="Records lost" value={lost} tone={lost > 0 ? "bad" : "good"} big foot={lost > 0 ? "deleted before a consumer read them" : "every consumer read everything Kafka kept"} />
            <Tile label="Kept in Kafka" value={formatRetention(r.retentionTargetMs)} big foot="every platform topic, age only" />
            <Tile label="Waiting in the journal" value={r.journal?.journal.unacknowledged} tone={r.journal?.catchingUp ? "warn" : undefined} big foot={r.journal?.catchingUp ? "Kafka has not confirmed them yet" : "on their way to Kafka"} />
            <Tile label="Parked" value={r.deadLetters.parked} tone={r.deadLetters.parked > 0 ? "warn" : undefined} big href="/storage/dead-letters" foot="dead letters waiting for a fix" />
            <Tile label="Rejected, archived" value={r.rejectedFrames.total} big href="/storage/rejected" foot={`${formatNumber(r.rejectedFrames.lastDay)} in the last 24 h`} />
          </Stats>

          {r.problem && <Callout tone="warn" title="Kafka did not answer">{r.problem}. Incoming frames wait in the journal; nothing is dropped.</Callout>}

          <Card icon={ShieldCheck} title="Checks" subtitle="FAIL means data was lost or can be lost now. WARN means nothing is lost, but a protection is weaker than it could be." live>
            <div className="safety-checks">
              {r.checks.map((c) => (
                <div className={`safety-check ${c.status.toLowerCase()}`} key={c.id}>
                  <Tag tone={TONE[c.status]}>{c.status}</Tag>
                  <div>
                    <div className="safety-check-title">{c.title}</div>
                    <div className="safety-check-detail">{c.detail}</div>
                  </div>
                </div>
              ))}
            </div>
          </Card>

          <Card icon={Workflow} title="How one frame is protected" subtitle="Each step holds the record until the next one has confirmed it.">
            <ol className="steps">
              <li>
                <span className="step-n">1</span>
                <span>
                  <b>Ingest journal.</b> The frame is written and fsynced to <Code>{r.journal?.directory ?? "the journal"}</Code> before the caller
                  continues, and stays until Kafka confirms it. Kafka down, the service killed, a power cut: it is sent again, in order.{" "}
                  <span className="faint">{formatNumber(r.journal?.journal.appended)} journaled, {formatNumber(r.journal?.journal.acknowledged)} confirmed since start.</span>
                </span>
              </li>
              <li>
                <span className="step-n">2</span>
                <span>
                  <b>Kafka.</b> Producers use <Code>acks={r.producer["acks"]}</Code> with idempotence; the broker keeps {copies} cop{copies === 1 ? "y" : "ies"},{" "}
                  {r.broker?.flushIntervalMessages === 1 ? "flushed to disk before every acknowledgement" : "replicated before the acknowledgement"}, for{" "}
                  {formatRetention(r.retentionTargetMs)}, deleted by age only.
                </span>
              </li>
              <li>
                <span className="step-n">3</span>
                <span>
                  <b>Consumers.</b> PostgreSQL, Redis or Kafka down: the consumer waits and retries for as long as it takes. A record that cannot be
                  handled goes to <Code>gps.dead-letters</Code> after 3 tries - never skipped. Kafka Streams parks in the same topic, inside its
                  exactly-once transaction.
                </span>
              </li>
              <li>
                <span className="step-n">4</span>
                <span>
                  <b>Archive.</b> Dead letters and rejected frames are stored in PostgreSQL for good (<Code>dead_letter</Code>, <Code>rejected_frame</Code>),
                  beyond Kafka&apos;s month. A dead letter is replayed or discarded with a note - never deleted.
                </span>
              </li>
              <li>
                <span className="step-n">5</span>
                <span>
                  <b>Proof.</b> Every consumer group&apos;s position is compared with what Kafka still keeps: a group that fell behind retention shows here
                  as lost records, and turns <Code>/actuator/health</Code> DOWN.
                </span>
              </li>
            </ol>
          </Card>

          <Card icon={Network} title="Consumer groups" subtitle="Lost = records Kafka deleted before this group read them. It must be 0." live flush>
            {r.groups.length === 0 ? (
              <Empty>No consumer group reported.</Empty>
            ) : (
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Group</th>
                      <th>State</th>
                      <th className="right">Consumers</th>
                      <th className="right">Waiting</th>
                      <th className="right">Lost</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.groups.map((g) => (
                      <tr key={g.group}>
                        <td className="mono">{g.group}</td>
                        <td>
                          <Tag tone={g.state.toUpperCase() === "STABLE" ? "good" : g.members === 0 ? "warn" : "neutral"}>{g.state.toUpperCase()}</Tag>
                        </td>
                        <td className="right mono">{g.members}</td>
                        <td className="right mono">{g.lag < 0 ? "–" : formatNumber(g.lag)}</td>
                        <td className={`right mono ${g.lostRecords > 0 ? "bad" : "good"}`}>{formatNumber(g.lostRecords)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <Card icon={Database} title="Topics" subtitle="Every platform topic, as the broker reports it." live flush>
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Topic</th>
                    <th className="right">Partitions</th>
                    <th className="right">Copies</th>
                    <th className="right">In sync before ok</th>
                    <th className="right">Kept for</th>
                    <th className="right">Records kept</th>
                    <th className="right">On disk</th>
                  </tr>
                </thead>
                <tbody>
                  {r.topics.map((t) => (
                    <tr key={t.topic}>
                      <td className="mono">{t.topic}</td>
                      <td className="right mono">{t.partitions}</td>
                      <td className={`right mono${t.replicationFactor < 3 ? " warn" : ""}`}>{t.replicationFactor}</td>
                      <td className="right mono">{t.minInsyncReplicas ?? "–"}</td>
                      <td className={`right mono${(t.retentionMs ?? 0) >= 0 && (t.retentionMs ?? 0) < r.retentionTargetMs ? " bad" : ""}`}>{formatRetention(t.retentionMs)}</td>
                      <td className="right mono">{formatNumber(t.recordsKept)}</td>
                      <td className="right mono muted">{formatBytes(t.bytesOnDisk)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <div className="grid-2">
            <Card icon={HardDrive} title="Ingest journal" subtitle="Frames and events on disk until Kafka confirms them." live>
              {!r.journal ? (
                <Empty>Not running: the direct flow sends no frames to Kafka.</Empty>
              ) : (
                <KeyValues
                  rows={[
                    ["State", <Tag key="s" tone={r.journal.catchingUp ? "warn" : "good"}>{r.journal.catchingUp ? "CATCHING UP" : "IN STEP WITH KAFKA"}</Tag>],
                    ["Waiting for Kafka", formatNumber(r.journal.journal.unacknowledged)],
                    ["Oldest waiting since", r.journal.journal.oldestUnacknowledgedSegmentAt ? formatAgo(r.journal.journal.oldestUnacknowledgedSegmentAt) : "–"],
                    ["Segments on disk", `${formatNumber(r.journal.journal.segments)} · ${formatBytes(r.journal.journal.bytesOnDisk)}`],
                    ["fsync every record", r.journal.fsyncEveryRecord ? "yes" : "no"],
                    ["Sends Kafka refused", formatNumber(r.journal.failedSends)],
                    ["Last refusal", r.journal.lastFailure ? `${formatAgo(r.journal.lastFailureAt)} · ${r.journal.lastFailure}` : "never"],
                  ]}
                />
              )}
            </Card>
            <Card icon={Archive} title="Broker" subtitle="The settings that decide what a broker can lose." live>
              {!r.broker ? (
                <Empty>Not reported.</Empty>
              ) : (
                <KeyValues
                  rows={[
                    ["Brokers", String(r.broker.brokers)],
                    ["Flush to disk before ok", r.broker.flushIntervalMessages === 1 ? "every record" : "left to the OS (replication)"],
                    ["Offsets of a stopped group kept", r.broker.offsetsRetentionMinutes ? formatRetention(r.broker.offsetsRetentionMinutes * 60_000) : "–"],
                    ["Auto-create topics", r.broker.autoCreateTopics === false ? "off" : "on"],
                    ["Default copies / min in sync", `${r.broker.defaultReplicationFactor ?? "–"} / ${r.broker.minInsyncReplicas ?? "–"}`],
                    ["Unclean leader election", r.broker.uncleanLeaderElection === "false" ? "off" : (r.broker.uncleanLeaderElection ?? "–")],
                  ]}
                />
              )}
            </Card>
          </div>

          <Callout title="One broker or three">
            With the single local broker every record has one copy, flushed to disk before Kafka acknowledges it - a crash or a power cut loses nothing,
            a lost disk would. <Code>docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d</Code> runs three brokers: three copies,
            two written before the acknowledgement. See <Link className="link" href="/developer/api">the API reference</Link> for the endpoints.
          </Callout>
        </>
      )}
    </div>
  );
}
