"use client";

import { useEffect, useState } from "react";
import { DatabaseZap, X } from "lucide-react";
import { formatNumber } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import type { ResetReport } from "@/lib/types";
import { post } from "@/lib/use-poll";

/**
 * "Empty DB" for demo practice: asks first, then calls POST /api/admin/reset and shows what was cleared.
 */
export function EmptyDbButton({ compact }: { compact?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="btn danger-soft" onClick={() => setOpen(true)}>
        <DatabaseZap size={15} />
        {compact ? "Empty DB" : "Empty DB"}
      </button>
      {open && <EmptyDbDialog onClose={() => setOpen(false)} />}
    </>
  );
}

function EmptyDbDialog({ onClose }: { onClose: () => void }) {
  const { refresh } = useOverview();
  const [restart, setRestart] = useState(true);
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<ResetReport>();
  const [problem, setProblem] = useState<string>();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !busy) onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  async function run() {
    setBusy(true);
    setProblem(undefined);
    try {
      const result = await post<ResetReport>(`/api/admin/reset?restartFleet=${restart}`);
      setReport(result);
      refresh();
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "The reset failed");
    } finally {
      setBusy(false);
    }
  }

  const deleted = report ? Object.values(report.kafkaRecordsDeleted).reduce((a, b) => a + b, 0) : 0;

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="empty-db-title">
        <div className="modal-head">
          <h2 id="empty-db-title" className="card-title">
            {report ? "Database emptied" : "Empty the database?"}
          </h2>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose} disabled={busy}>
            <X size={18} />
          </button>
        </div>

        {!report ? (
          <>
            <p className="muted" style={{ marginBottom: 14 }}>
              For demo practice: starts the platform again from nothing so you can cross-check every run. Nothing is restarted.
            </p>
            <ul className="modal-list">
              <li>Stops the simulated fleet and pauses every Kafka consumer</li>
              <li>Deletes every message in the four Kafka topics, and moves each consumer past them</li>
              <li>Empties positions, trips, stops, events and the trip detector's state in PostgreSQL</li>
              <li>Deletes the live positions, vehicle states, history and engine counters in Redis</li>
            </ul>
            <p className="faint" style={{ fontSize: 12.5, margin: "12px 0 16px" }}>
              Kept: vehicles, devices, companies and their assignments, and your speed rules.
            </p>
            <label className="check">
              <input type="checkbox" checked={restart} onChange={(e) => setRestart(e.target.checked)} />
              Start the fleet again afterwards
            </label>
            {problem && <p className="bad" style={{ marginTop: 12 }}>{problem}</p>}
            <div className="card-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn soft" onClick={onClose} disabled={busy}>
                Cancel
              </button>
              <button type="button" className="btn danger" onClick={run} disabled={busy}>
                {busy ? "Emptying (about 6 s)" : "Empty DB"}
              </button>
            </div>
          </>
        ) : (
          <>
            <dl className="kv">
              <div className="kv-row">
                <dt>Kafka messages deleted</dt>
                <dd className="mono">{formatNumber(deleted)}</dd>
              </div>
              <div className="kv-row">
                <dt>Tables emptied</dt>
                <dd className="mono">{report.tablesEmptied.length}</dd>
              </div>
              <div className="kv-row">
                <dt>Redis keys deleted</dt>
                <dd className="mono">{formatNumber(report.redisKeysDeleted)}</dd>
              </div>
              <div className="kv-row">
                <dt>Consumers moved to the end</dt>
                <dd className="mono">{report.listenersSkippedToEnd}</dd>
              </div>
              <div className="kv-row">
                <dt>Fleet</dt>
                <dd>{report.fleetRestarted ? "started again, catching up from 2 hours ago" : "stopped"}</dd>
              </div>
              <div className="kv-row">
                <dt>Took</dt>
                <dd className="mono">{(report.tookMillis / 1000).toFixed(1)} s</dd>
              </div>
            </dl>
            {report.notes.length > 0 && (
              <ul className="modal-list warn">
                {report.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            )}
            <div className="card-actions" style={{ justifyContent: "flex-end" }}>
              <button type="button" className="btn primary" onClick={onClose}>
                Done
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
