"use client";

import { FileX, ListChecks } from "lucide-react";
import { Callout, Card, Code, PageHeader, Stats, Tile } from "@/components/ops/kit";
import { formatAgo, formatPercent, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, consumer, ingress, producer } from "@/lib/pipeline";

const REASONS: [string, string][] = [
  ["IMEI is missing", "The frame has no device id; nothing can be linked to it."],
  ["Recording time is missing", "No timestamp, so the reading cannot be ordered."],
  ["Recording time … is in the future", "More than 5 minutes ahead: a device clock problem."],
  ["Latitude or longitude is missing", "No position at all."],
  ["Latitude … is outside -90..90", "Corrupt or wrongly parsed coordinate."],
  ["Longitude … is outside -180..180", "Corrupt or wrongly parsed coordinate."],
  ["Position is 0,0 - the device has no GPS fix", "The device sent before it found satellites."],
  ["Speed is missing", "No speedometer value."],
  ["Speed … km/h is outside 0..150", "Not believable for a truck."],
];

export default function RejectedFramesPage() {
  const { data: o } = useOverview();
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const logger = consumer(o, STAGE.rejectedFrameLogger);
  const framesIn = ingress(o);
  const share = rejected && framesIn?.totalMessages ? rejected.totalMessages / framesIn.totalMessages : 0;

  return (
    <div className="stack">
      <PageHeader
        title="Rejected frames"
        description="Records that failed the schema. They are never stored; each is published with its reason so device problems can be found and fixed."
        meta={[
          ["Topic", <Code key="t">gps.rejected-frames</Code>],
          ["Logged by", <Code key="g">gps-rejected-frame-logger</Code>],
        ]}
      />

      <Stats>
        <Tile label="Rejected since start" value={rejected?.totalMessages ?? (o ? 0 : undefined)} tone={rejected?.totalMessages ? "warn" : undefined} big />
        <Tile label="Share of frames" value={o ? share * 100 : undefined} format={(v) => v.toFixed(3)} unit="%" big />
        <Tile label="Rejected now" value={rejected?.messagesPerSecond} format={formatRate} unit="/s" big />
        <Tile label="Last rejection" value={rejected ? formatAgo(rejected.lastMessageAt) : "–"} big foot={`${logger ? formatRate(logger.load.messagesPerSecond) : "–"} /s logged`} />
      </Stats>

      <Card icon={ListChecks} title="Possible reasons" subtitle="The first rule a frame breaks is its reason." flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Reason</th>
                <th>What it usually means</th>
              </tr>
            </thead>
            <tbody>
              {REASONS.map(([reason, meaning]) => (
                <tr key={reason}>
                  <td className="mono">{reason}</td>
                  <td className="muted wrap">{meaning}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Callout title="Seeing individual rejected frames">
        The logger writes each rejected frame and its reason to the service log. A per-reason count and a browsable list need a small endpoint
        (for example <Code>GET /api/rejected-frames</Code>); this page will show them once it exists.
      </Callout>

      <Card icon={FileX} title="Share of frames rejected" live>
        <div className="purity">
          <span className="purity-value mono">{formatPercent(share)}</span>
          <span className="muted">of all frames since the service started failed validation.</span>
        </div>
      </Card>
    </div>
  );
}
