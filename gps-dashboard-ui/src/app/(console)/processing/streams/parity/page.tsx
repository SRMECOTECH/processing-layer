"use client";

import { GitCompareArrows, ListChecks, Scale } from "lucide-react";
import { Callout, Card, Code, Empty, KeyValues, PageHeader, Progress, Stats, Tag, Tile } from "@/components/ops/kit";
import { StreamsOff, formatSeconds, useStreamsOverview } from "@/components/ops/streams";
import { formatNumber, formatPercent, formatTime } from "@/lib/format";
import type { ParityTally, ParityView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function StreamsParityPage() {
  const overview = useStreamsOverview(10_000);
  const enabled = overview.data?.enabled;
  const parity = usePoll<ParityView>(enabled ? "/api/streams/parity?limit=40" : null, 4000);
  const p = parity.data;
  const t = p?.totals;
  const compared = t ? t.matched + t.mismatched + t.warmup + t.onlyListener + t.onlyStreams : 0;
  const agreeing = t ? t.matched + t.warmup : 0;

  return (
    <div className="stack">
      <PageHeader
        description="The safe way to hand normalization to Kafka Streams: the listener keeps producing the real topics, Kafka Streams runs the same logic into gps.shadow.*, and a windowed outer join pairs the two results of every reading on IMEI and recordedAt. Switch to owner only when they agree."
        badges={p ? <Tag tone={p.active ? "good" : "neutral"}>{p.active ? "SHADOW RUNNING" : "NOT IN SHADOW MODE"}</Tag> : undefined}
        meta={[
          ["Mode", <Code key="m">gps.streams.normalization={overview.data?.normalization ?? "–"}</Code>],
          ["Join", "IMEI + recordedAt, window 0"],
          ["Waits for the other side", formatSeconds(overview.data?.settings?.parityGraceSeconds)],
          ["Store", <Code key="s">parity-tally-store</Code>],
        ]}
      />

      {!enabled ? (
        <StreamsOff overview={overview.data} error={overview.error} />
      ) : !p ? (
        <Empty>{parity.error ?? "Waiting for the service."}</Empty>
      ) : !p.active ? (
        <>
          <Callout title="Parity runs in shadow mode">
            {p.note} Start the service with <Code>GPS_STREAMS_NORMALIZATION=shadow</Code> (Docker) or{" "}
            <Code>--gps.streams.normalization=shadow</Code>. The listener then owns the real topics again and this page fills in.
          </Callout>
          <HandOverSteps />
        </>
      ) : (
        <>
          <Stats columns={5}>
            <Tile label="Readings compared" value={compared} big />
            <Tile label="Agree" value={compared ? formatPercent(agreeing / compared) : "–"} tone={compared && agreeing / compared > 0.99 ? "good" : "warn"} big progress={compared ? agreeing / compared : 0} />
            <Tile label="Mismatched" value={t?.mismatched} tone={t && t.mismatched > 0 ? "bad" : undefined} big foot="same reading, a field differs" />
            <Tile label="Only the listener" value={t?.onlyListener} tone={t && t.onlyListener > 0 ? "warn" : undefined} big foot="Kafka Streams rejected it" />
            <Tile label="Only Kafka Streams" value={t?.onlyStreams} tone={t && t.onlyStreams > 0 ? "warn" : undefined} big foot="the listener rejected it" />
          </Stats>

          <div className="grid-main-side">
            <Card icon={GitCompareArrows} title="Vehicles with differences first" subtitle="Per vehicle, from the parity KTable. Warm-up = the shadow's first reading of a device, measured from nothing." live flush>
              {p.vehicles.length === 0 ? (
                <Empty>Nothing compared yet: a reading is compared once both sides produced it, or once the grace period passed.</Empty>
              ) : (
                <div className="data-table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>IMEI</th>
                        <th className="right">Match</th>
                        <th className="right">Mismatch</th>
                        <th className="right">Warm-up</th>
                        <th className="right">Only listener</th>
                        <th className="right">Only streams</th>
                        <th>Last difference</th>
                      </tr>
                    </thead>
                    <tbody>
                      {p.vehicles.map((v) => (
                        <tr key={v.imei}>
                          <td className="mono">{v.imei}</td>
                          <td className="right mono">{formatNumber(v.tally.matched)}</td>
                          <td className={`right mono${v.tally.mismatched ? " bad" : " faint"}`}>{formatNumber(v.tally.mismatched)}</td>
                          <td className="right mono faint">{formatNumber(v.tally.warmup)}</td>
                          <td className={`right mono${v.tally.onlyListener ? " warn" : " faint"}`}>{formatNumber(v.tally.onlyListener)}</td>
                          <td className={`right mono${v.tally.onlyStreams ? " warn" : " faint"}`}>{formatNumber(v.tally.onlyStreams)}</td>
                          <td className="muted wrap">{lastDifference(v.tally)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            <div className="stack">
              <Card icon={Scale} title="Rejections" subtitle="Counted since this service started, not joined reading by reading.">
                <KeyValues
                  rows={[
                    ["Listener accepted", formatNumber(p.listenerAccepted)],
                    ["Kafka Streams accepted", formatNumber(p.streamsAccepted)],
                    ["Listener rejected", formatNumber(p.listenerRejected)],
                    ["Kafka Streams rejected", formatNumber(p.streamsRejected)],
                  ]}
                />
                <div className="st-split">
                  <span className="faint">Agreement</span>
                  <Progress value={compared ? agreeing / compared : 0} tone="good" />
                </div>
              </Card>
              <HandOverSteps />
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function lastDifference(tally: ParityTally): string {
  const d = tally.lastDifference;
  if (!d) return "–";
  const what = d.outcome === "ONLY_LISTENER" ? "only the listener produced it" : d.outcome === "ONLY_STREAMS" ? "only Kafka Streams produced it" : d.differences.join("; ");
  return `${formatTime(d.recordedAt)} · ${d.outcome.toLowerCase().replace("_", " ")}: ${what}`;
}

function HandOverSteps() {
  return (
    <Card icon={ListChecks} title="Hand-over, step by step">
      <ol className="steps">
        <li>
          <span className="step-n">1</span>
          <span>
            Run <Code>shadow</Code> for a while with live traffic and a demo reset or two.
          </span>
        </li>
        <li>
          <span className="step-n">2</span>
          <span>Expect only warm-up and explained differences: re-sent older readings and stale readings that Kafka Streams rejects on purpose.</span>
        </li>
        <li>
          <span className="step-n">3</span>
          <span>
            Switch to <Code>owner</Code>. The normalizer joins the listener&apos;s consumer group and continues from its committed offsets: no replay, no gap.
          </span>
        </li>
        <li>
          <span className="step-n">4</span>
          <span>
            To roll back, switch to <Code>off</Code> or <Code>shadow</Code>: the listener continues from Kafka Streams&apos; offsets the same way.
          </span>
        </li>
      </ol>
    </Card>
  );
}
