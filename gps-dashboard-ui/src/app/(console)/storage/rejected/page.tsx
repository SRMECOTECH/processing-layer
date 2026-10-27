"use client";

import { useState } from "react";
import { Archive, ChartBar, ListChecks, Search } from "lucide-react";
import { Card, Code, Empty, PageHeader, Stats, Tile } from "@/components/ops/kit";
import { formatAgo, formatDateTime, formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { STAGE, ingress, producer } from "@/lib/pipeline";
import type { RejectedFrameRow, RejectedSummary } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

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
  ["Duplicate reading", "The device sent a reading it had already sent (Kafka Streams also catches re-sent older ones)."],
  ["Stale reading", "More than 24 h older than the device's newest reading (Kafka Streams)."],
  ["Record key … is not the frame's IMEI", "The producer keyed the frame wrongly, so the device's order is not guaranteed (Kafka Streams)."],
];

export default function RejectedFramesPage() {
  const { data: o } = useOverview();
  const [imei, setImei] = useState("");
  const summary = usePoll<RejectedSummary>("/api/rejected-frames/summary", 5000);
  const rows = usePoll<RejectedFrameRow[]>(`/api/rejected-frames?limit=100${imei.trim() ? `&imei=${encodeURIComponent(imei.trim())}` : ""}`, 5000);
  const rejected = producer(o, STAGE.rejectedFrameProducer);
  const framesIn = ingress(o);
  const share = rejected && framesIn?.totalMessages ? rejected.totalMessages / framesIn.totalMessages : 0;
  const s = summary.data;

  return (
    <div className="stack">
      <PageHeader
        title="Rejected frames"
        description="Frames that failed validation never become positions, but they are never thrown away either: each is published with its reason and archived in PostgreSQL with the payload exactly as it arrived - kept beyond Kafka's month, so device problems can be traced at any time."
        meta={[
          ["Topic", <Code key="t">gps.rejected-frames</Code>],
          ["Archive", <Code key="a">rejected_frame (PostgreSQL)</Code>],
          ["Archived by", <Code key="g">gps-rejected-frame-logger</Code>],
        ]}
      />

      <Stats>
        <Tile label="Archived, all time" value={s?.total} big foot={s?.newest ? `newest ${formatAgo(s.newest)}` : "none yet"} />
        <Tile label="Last 24 hours" value={s?.lastDay} tone={s && s.lastDay > 0 ? "warn" : undefined} big />
        <Tile label="Share of frames" value={o ? share * 100 : undefined} format={(v) => v.toFixed(3)} unit="%" big foot="since the service started" />
        <Tile label="Rejected now" value={rejected?.messagesPerSecond} format={formatRate} unit="/s" big />
      </Stats>

      <div className="grid-main-side">
        <Card icon={Archive} title="Archived rejections" subtitle="Newest first, with the payload as the device sent it." live flush>
          <div className="table-toolbar">
            <label className="search-field">
              <Search size={15} />
              <input type="search" placeholder="Exact IMEI" value={imei} onChange={(e) => setImei(e.target.value)} aria-label="Filter by IMEI" />
            </label>
            <span className="faint">{rows.data ? `${rows.data.length} shown` : ""}</span>
          </div>
          {!rows.data ? (
            <Empty>{rows.error ?? "Waiting for the service."}</Empty>
          ) : rows.data.length === 0 ? (
            <Empty>No rejected frame archived{imei.trim() ? " for this IMEI" : ""}.</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Rejected</th>
                    <th>IMEI</th>
                    <th>Reason</th>
                    <th>Payload</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.data.map((r) => (
                    <tr key={r.id}>
                      <td className="muted nowrap">{formatDateTime(r.rejectedAt)}</td>
                      <td className="mono">{r.imei ?? "–"}</td>
                      <td className="wrap">{r.rejectionReason}</td>
                      <td className="mono faint wrap">{r.originalPayload.length > 140 ? `${r.originalPayload.slice(0, 140)}…` : r.originalPayload}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card icon={ChartBar} title="By reason" subtitle="Last 24 h / all time." live flush>
          {!s || s.byReason.length === 0 ? (
            <Empty>Nothing archived yet.</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Reason</th>
                    <th className="right">24 h</th>
                    <th className="right">All</th>
                  </tr>
                </thead>
                <tbody>
                  {s.byReason.map((r) => (
                    <tr key={r.reason}>
                      <td className="wrap">{r.reason}</td>
                      <td className="right mono">{formatNumber(r.lastDay)}</td>
                      <td className="right mono">{formatNumber(r.total)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

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
    </div>
  );
}
