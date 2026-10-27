"use client";

import { useState } from "react";
import { Archive, RotateCcw, ScanSearch, Undo2 } from "lucide-react";
import { Callout, Card, Code, Empty, KeyValues, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatDateTime, formatNumber } from "@/lib/format";
import type { DeadLetterRow, DeadLetterSummary } from "@/lib/types";
import { post, usePoll } from "@/lib/use-poll";

type Filter = "PARKED" | "REPLAYED" | "DISCARDED" | "ALL";

export default function DeadLettersPage() {
  const [filter, setFilter] = useState<Filter>("PARKED");
  const [picked, setPicked] = useState<number | null>(null);
  const [message, setMessage] = useState<string>();
  const summary = usePoll<DeadLetterSummary>("/api/dead-letters/summary", 5000);
  const list = usePoll<DeadLetterRow[]>(`/api/dead-letters?limit=200${filter === "ALL" ? "" : `&status=${filter}`}`, 5000);
  const detail = usePoll<DeadLetterRow>(picked === null ? null : `/api/dead-letters/${picked}`, 10_000);
  const s = summary.data;

  async function act(path: string, done: string) {
    try {
      await post<unknown>(path);
      setMessage(done);
    } catch (problem) {
      setMessage(problem instanceof Error ? problem.message : "The service refused");
    }
    summary.refresh();
    list.refresh();
    detail.refresh();
  }

  return (
    <div className="stack">
      <PageHeader
        description="Records a consumer could not handle - a message it cannot read, a database rule it breaks, a bug - are parked here instead of skipped: by the listeners and by both Kafka Streams apps, with where they came from and the error. Fix the cause, then replay them into their original topic, or discard them with a reason. Nothing is ever deleted."
        meta={[
          ["Topic", <Code key="t">gps.dead-letters</Code>],
          ["Archive", <Code key="a">dead_letter (PostgreSQL)</Code>],
          ["Archived by", <Code key="g">gps-dead-letter-archive</Code>],
        ]}
        actions={
          s && s.parked > 0 ? (
            <button type="button" className="btn primary" onClick={() => act("/api/dead-letters/replay?limit=200", "Replayed the oldest parked dead letters")}>
              <RotateCcw size={15} /> Replay parked
            </button>
          ) : undefined
        }
      />

      <Stats>
        <Tile label="Parked" value={s?.parked} tone={s && s.parked > 0 ? "warn" : "good"} big foot={s?.oldestParkedAt ? `oldest ${formatAgo(s.oldestParkedAt)}` : "nothing waiting"} />
        <Tile label="Replayed" value={s?.replayed} big foot="sent back to their topic, confirmed" />
        <Tile label="Discarded" value={s?.discarded} big foot="kept, with the reason" />
        <Tile label="Total archived" value={s ? s.parked + s.replayed + s.discarded : undefined} big />
      </Stats>

      {message && <Callout title="Last action">{message}</Callout>}

      <div className="grid-main-side">
        <Card
          icon={Archive}
          title="Dead letters"
          subtitle="Newest first. Click one to see the record and the stack trace."
          live
          flush
          actions={
            <Segmented<Filter>
              label="Status"
              value={filter}
              onChange={(f) => {
                setFilter(f);
                setPicked(null);
              }}
              options={[
                ["PARKED", "Parked"],
                ["REPLAYED", "Replayed"],
                ["DISCARDED", "Discarded"],
                ["ALL", "All"],
              ]}
            />
          }
        >
          {!list.data ? (
            <Empty>{list.error ?? "Waiting for the service."}</Empty>
          ) : list.data.length === 0 ? (
            <Empty>{filter === "PARKED" ? "Nothing parked: every record was handled." : "None."}</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Parked</th>
                    <th>From</th>
                    <th>Key</th>
                    <th>Error</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {list.data.map((d) => (
                    <tr key={d.id} className={`clickable${picked === d.id ? " selected" : ""}`} onClick={() => setPicked(d.id)}>
                      <td className="muted nowrap">{formatDateTime(d.parkedAt)}</td>
                      <td className="mono">
                        {d.originalTopic ?? "?"}-{d.originalPartition ?? "?"}@{d.originalOffset ?? "?"}
                        <div className="faint">{d.consumerGroup ?? d.source}</div>
                      </td>
                      <td className="mono muted">{d.recordKey ?? "–"}</td>
                      <td className="muted wrap">
                        <span className="mono">{shortClass(d.exceptionClass)}</span> {d.exceptionMessage}
                      </td>
                      <td>
                        <Tag tone={d.status === "PARKED" ? "warn" : d.status === "REPLAYED" ? "good" : "neutral"}>{d.status}</Tag>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <div className="stack">
          <Card icon={ScanSearch} title="Dead letter" subtitle={picked === null ? "Pick one" : `#${picked}`} live={picked !== null ? true : undefined}>
            {picked === null ? (
              <Empty>Pick a dead letter to see its record, its error and what to do with it.</Empty>
            ) : !detail.data ? (
              <Empty>{detail.error ?? "Loading…"}</Empty>
            ) : (
              <DeadLetterDetail
                row={detail.data}
                onReplay={() => act(`/api/dead-letters/${detail.data!.id}/replay`, `Dead letter #${detail.data!.id} replayed into ${detail.data!.originalTopic}`)}
                onDiscard={(note) => act(`/api/dead-letters/${detail.data!.id}/discard?note=${encodeURIComponent(note)}`, `Dead letter #${detail.data!.id} discarded`)}
              />
            )}
          </Card>

          <Card icon={Undo2} title="Where they come from" subtitle="Parked now / ever, by topic and by error.">
            {!s || s.byTopic.length === 0 ? (
              <Empty>Nothing archived yet.</Empty>
            ) : (
              <KeyValues
                rows={[
                  ...s.byTopic.map((t): [string, string] => [t.name, `${formatNumber(t.parked)} / ${formatNumber(t.total)}`]),
                  ...s.byException.slice(0, 5).map((e): [string, string] => [shortClass(e.name), `${formatNumber(e.parked)} / ${formatNumber(e.total)}`]),
                ]}
              />
            )}
          </Card>
        </div>
      </div>

      <Callout title="Replaying is safe">
        A replayed record goes back to its original topic, so every consumer group of that topic sees it again - not only the one that failed. That is
        fine: storage, trips and the normalizer are idempotent (same IMEI and time, same row), and the processors skip a reading they have already seen.
      </Callout>
    </div>
  );
}

function shortClass(name: string | null): string {
  if (!name) return "–";
  return name.substring(name.lastIndexOf(".") + 1);
}

function DeadLetterDetail({ row, onReplay, onDiscard }: { row: DeadLetterRow; onReplay: () => void; onDiscard: (note: string) => void }) {
  const [note, setNote] = useState("");
  return (
    <div className="stack tight">
      <KeyValues
        rows={[
          ["Status", <Tag key="s" tone={row.status === "PARKED" ? "warn" : row.status === "REPLAYED" ? "good" : "neutral"}>{row.status}</Tag>],
          ["Parked by", row.source === "kafka-streams" ? "Kafka Streams" : `listener · ${row.consumerGroup ?? "?"}`],
          ["Came from", <span key="o" className="mono">{row.originalTopic}-{row.originalPartition}@{row.originalOffset}</span>],
          ["Parked", formatDateTime(row.parkedAt)],
          ["Replayed", row.replayCount ? `${row.replayCount}× · last ${formatAgo(row.lastReplayedAt)}` : "never"],
          ...(row.resolutionNote ? [["Note", row.resolutionNote] as [string, string]] : []),
        ]}
      />
      <div className="inner-title st-section">Record</div>
      <pre className="code-block">{row.recordValue ?? "(empty)"}</pre>
      <div className="inner-title st-section">Error</div>
      <pre className="code-block small">{`${row.exceptionClass ?? ""}: ${row.exceptionMessage ?? ""}\n\n${row.stacktrace ?? ""}`}</pre>
      {row.status !== "DISCARDED" && (
        <div className="dl-actions">
          <button type="button" className="btn primary" onClick={onReplay}>
            <RotateCcw size={15} /> Replay into {row.originalTopic}
          </button>
          {row.status === "PARKED" && (
            <>
              <input className="input-box" placeholder="Why discard it? (kept with the record)" value={note} onChange={(e) => setNote(e.target.value)} aria-label="Discard reason" />
              <button type="button" className="btn soft" disabled={note.trim().length === 0} onClick={() => onDiscard(note)}>
                Discard
              </button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
