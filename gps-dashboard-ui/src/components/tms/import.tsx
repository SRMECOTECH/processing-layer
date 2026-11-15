"use client";

import { useRef, useState } from "react";
import { CheckCheck, FileCheck, FileText, History, TriangleAlert, Upload } from "@/components/icons";
import { Callout, Card, Code, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatDateTime } from "@/lib/format";
import { upload, type ImportReport } from "@/lib/tms";
import { usePoll } from "@/lib/use-poll";
import { useViewer } from "@/lib/viewer";
import s from "./tms.module.css";

const HINT_LABEL: Record<string, string> = {
  NO_DEVICE: "No device on this truck",
  UNMAPPED: "The file's IMEI is not mapped to this truck yet",
  DIFFERENT_DEVICE: "Another device is mapped to this truck",
  DEVICE_IN_OTHER_TRUCK: "The file's IMEI is fitted in another truck",
  MAPPED_FROM_FILE: "Mapped from the file now",
};

type ImportRow = {
  id: number;
  file_name: string;
  format: string;
  uploaded_by: string;
  uploaded_role: string;
  company: string | null;
  rows_total: number;
  rows_created: number;
  rows_updated: number;
  rows_unchanged: number;
  rows_failed: number;
  uploaded_at: string;
};

/**
 * Upload of a transporter's shipment file: first a preview that writes nothing (which column is which field, what
 * would be new, changed or unchanged, which rows are refused and why), then the import. The same file twice is
 * harmless: its rows are found unchanged.
 */
export function ImportPanel() {
  const viewer = useViewer();
  const [file, setFile] = useState<File | null>(null);
  const [pasted, setPasted] = useState("");
  const [report, setReport] = useState<ImportReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [applyMappings, setApplyMappings] = useState(false);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const history = usePoll<ImportRow[]>("/api/portal/shipments/imports", 30_000);

  const content = (): [Blob | string, string] | null => (file ? [file, file.name] : pasted.trim() ? [pasted, "pasted-rows.txt"] : null);

  const run = async (commit: boolean) => {
    const body = content();
    if (!body) return;
    setBusy(true);
    setError(null);
    try {
      const path = commit ? `/api/portal/shipments/import${applyMappings ? "?applyMappings=true" : ""}` : "/api/portal/shipments/import/preview";
      setReport(await upload<ImportReport>(path, body[0], body[1]));
      if (commit) history.refresh();
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "The upload failed");
    } finally {
      setBusy(false);
    }
  };

  const choose = (f: File | null) => {
    setFile(f);
    setReport(null);
    if (f) setPasted("");
  };

  return (
    <div className="stack">
      <PageHeader
        title="Upload shipments"
        description={
          viewer.role === "TRANSPORTER"
            ? "Your shipment file, as the consigner's TMS exports it (Excel or CSV), or rows copied out of it. Every row becomes a shipment carried by you; uploading the same file again changes nothing."
            : "A shipment file from a transporter or the consigner's TMS: Excel (.xlsx, .xls), CSV, or rows copied out of the TMS database. Each row's transporter is taken from the row (vendor code first, then name)."
        }
        actions={
          <a className="btn soft" href="/api/portal/shipments/template">
            <FileText size={14} /> Template (CSV)
          </a>
        }
      />

      <div className="grid-main-side">
        <Card icon={Upload} title="File" subtitle="Preview first: nothing is written until you import">
          <div className="stack">
            <div
              className={`${s.drop}${dragging ? ` ${s.dropActive}` : ""}`}
              role="button"
              tabIndex={0}
              onClick={() => input.current?.click()}
              onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && input.current?.click()}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                choose(e.dataTransfer.files[0] ?? null);
              }}
            >
              <Upload size={22} />
              <span className={s.dropTitle}>{file ? file.name : "Drop the shipment file here, or click to choose"}</span>
              <span className={s.dropNote}>
                {file ? `${(file.size / 1024).toFixed(1)} KB` : ".xlsx · .xls · .csv · .txt, up to 10 MB and 20,000 rows"}
              </span>
              <input
                ref={input}
                type="file"
                accept=".xlsx,.xls,.csv,.txt,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel"
                hidden
                onChange={(e) => choose(e.target.files?.[0] ?? null)}
              />
            </div>
            <label className="field">
              <span className="field-label">…or paste rows</span>
              <textarea
                className={s.paste}
                placeholder="'754710', '540', 'local', 'TATA STEEL-JSR', 'JH05CY2516', '0869925072822461', 'TRAILER', NULL, ..."
                value={pasted}
                onChange={(e) => {
                  setPasted(e.target.value);
                  setFile(null);
                  setReport(null);
                }}
              />
              <span className="field-hint">With a header row (any of the names in the template), or without one in the tta_trips column order.</span>
            </label>
            {viewer.role === "DEVELOPER" && (
              <label className="switch-row">
                <span>
                  <span className="switch-title">Map devices from the file</span>
                  <span className="switch-sub">Where a truck has no device yet, fit the file&apos;s IMEI in it (s_device_id)</span>
                </span>
                <input type="checkbox" checked={applyMappings} onChange={(e) => setApplyMappings(e.target.checked)} />
              </label>
            )}
            {error && (
              <Callout tone="warn" title="Not imported">
                {error}
              </Callout>
            )}
            <div className={s.actions}>
              <button type="button" className="btn soft" disabled={busy || !content()} onClick={() => run(false)}>
                <FileCheck size={14} /> Preview
              </button>
              <button
                type="button"
                className="btn primary"
                disabled={busy || !content() || !report || report.committed || report.rowsValid === 0}
                onClick={() => run(true)}
              >
                <CheckCheck size={14} /> Import {report && !report.committed ? `${report.rowsValid} rows` : ""}
              </button>
              {busy && <span className={s.small}>Working…</span>}
            </div>
          </div>
        </Card>

        <Card icon={History} title="Earlier uploads" flush>
          {!history.data ? (
            <Empty>Loading.</Empty>
          ) : history.data.length === 0 ? (
            <Empty>Nothing uploaded yet.</Empty>
          ) : (
            <ul className="feed">
              {history.data.slice(0, 12).map((h) => (
                <li key={h.id} className="feed-row">
                  <span className={`status-dot ${h.rows_failed > 0 ? "warn" : "good"}`} />
                  <span className="feed-main">
                    <span className="feed-title">{h.file_name}</span>
                    <span className="feed-sub">
                      {h.rows_created} new · {h.rows_updated} changed · {h.rows_unchanged} unchanged · {h.rows_failed} refused · by {h.uploaded_by}
                      {h.company ? ` (${h.company})` : ""}
                    </span>
                  </span>
                  <span className="feed-side faint">{formatDateTime(h.uploaded_at)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      {report && <ReportView report={report} />}
    </div>
  );
}

function ReportView({ report }: { report: ImportReport }) {
  const blocking = report.problems.filter((p) => p.blocking);
  const warnings = report.problems.filter((p) => !p.blocking);
  return (
    <>
      {report.committed ? (
        <Callout title="Imported">
          {report.created} new and {report.updated} changed shipments written, {report.unchanged} unchanged rows left alone
          {report.closedByNextUpload > 0 ? `, ${report.closedByNextUpload} earlier trips of the same trucks closed (next upload)` : ""}. The
          shipment-tracking module now follows them with the trucks&apos; GPS.
        </Callout>
      ) : (
        <Callout tone="info" title="Preview: nothing written yet">
          Read as <strong>{report.format}</strong>, {report.layout === "tta_trips" ? "without a header, by the tta_trips column order" : "by its header row"}.
          {report.importedBefore && ` This exact file was imported before, on ${formatDateTime(report.importedBefore)}: importing it again changes nothing.`}
        </Callout>
      )}

      <Stats columns={5}>
        <Tile label="Rows" value={report.rowsTotal} />
        <Tile label="New shipments" value={report.created} tone={report.created > 0 ? "good" : undefined} />
        <Tile label="Changed" value={report.updated} />
        <Tile label="Unchanged" value={report.unchanged} foot="already there, left alone" />
        <Tile label="Refused" value={report.rowsFailed} tone={report.rowsFailed > 0 ? "bad" : undefined} />
      </Stats>

      <div className="grid-2">
        <Card icon={FileCheck} title="Which column is which" subtitle={`${Object.keys(report.columns).length} fields found`} flush>
          {report.missingRequired.length > 0 && (
            <div style={{ padding: "0 var(--card-pad) 12px" }}>
              <Callout tone="warn" title="Required columns missing">
                {report.missingRequired.join(", ")}
              </Callout>
            </div>
          )}
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Field</th>
                  <th>Read from</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(report.columns).map(([field, column]) => (
                  <tr key={field}>
                    <td>{field.replaceAll("_", " ").toLowerCase()}</td>
                    <td>
                      <Code>{column}</Code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {report.ignoredColumns.length > 0 && <p className={s.small} style={{ padding: "10px var(--card-pad)" }}>Not used: {report.ignoredColumns.join(", ")}</p>}
        </Card>

        <Card icon={TriangleAlert} title="Before you rely on it" subtitle="Places without a geofence, new companies, device mapping">
          <div className="stack">
            {Object.keys(report.unknownPlaces).length === 0 && report.newCompanies.length === 0 && report.mappingHints.length === 0 && (
              <Empty>Every place has a geofence, every company is known and every truck has its device.</Empty>
            )}
            {Object.keys(report.unknownPlaces).length > 0 && (
              <div>
                <div className="field-label">No geofence (arrival / departure cannot be seen)</div>
                <div className="chip-row" style={{ marginTop: 6 }}>
                  {Object.entries(report.unknownPlaces).map(([place, count]) => (
                    <Tag key={place} tone="warn">
                      {place} · {count}
                    </Tag>
                  ))}
                </div>
              </div>
            )}
            {report.newCompanies.length > 0 && (
              <div>
                <div className="field-label">New companies the import adds</div>
                <div className="chip-row" style={{ marginTop: 6 }}>
                  {report.newCompanies.map((c) => (
                    <Tag key={c} tone="accent">
                      {c}
                    </Tag>
                  ))}
                </div>
              </div>
            )}
            {report.mappingHints.length > 0 && (
              <div className="data-table-wrap">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Truck</th>
                      <th>IMEI in file</th>
                      <th>Mapped IMEI</th>
                      <th>Device</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.mappingHints.slice(0, 40).map((h) => (
                      <tr key={h.vehicleNo}>
                        <td className="mono">{h.vehicleNo}</td>
                        <td className="mono">{h.imeiInFile ?? "–"}</td>
                        <td className="mono">{h.mappedImei ?? "–"}</td>
                        <td>
                          <Tag tone={h.state === "MAPPED_FROM_FILE" ? "good" : "warn"}>{HINT_LABEL[h.state] ?? h.state}</Tag>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </Card>
      </div>

      {(blocking.length > 0 || warnings.length > 0) && (
        <Card icon={TriangleAlert} title="Rows with problems" subtitle={`${blocking.length} refused, ${warnings.length} warnings`} flush>
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th className="right">Row</th>
                  <th>Trip</th>
                  <th>Problem</th>
                  <th>Effect</th>
                </tr>
              </thead>
              <tbody>
                {[...blocking, ...warnings].map((p, i) => (
                  <tr key={i} className={p.blocking ? "flagged" : undefined}>
                    <td className="right mono">{p.row}</td>
                    <td className="mono">{p.tripNo ?? "–"}</td>
                    <td className={s.wrap}>{p.message}</td>
                    <td>{p.blocking ? <Tag tone="bad">refused</Tag> : <Tag tone="warn">imported</Tag>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {report.sample.length > 0 && (
        <Card icon={FileText} title="First rows as read" flush>
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  {Object.keys(report.sample[0]).map((k) => (
                    <th key={k}>{k}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {report.sample.map((row, i) => (
                  <tr key={i}>
                    {Object.entries(row).map(([k, value]) => (
                      <td key={k} className={k === "dispatchedAt" || k === "etaAt" ? "muted" : undefined}>
                        {value === null ? "–" : k === "dispatchedAt" || k === "etaAt" ? formatDateTime(String(value)) : String(value)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
}
