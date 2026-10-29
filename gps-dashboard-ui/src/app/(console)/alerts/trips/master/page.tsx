"use client";

import { useMemo, useState } from "react";
import { ArrowRight, Building2, Cpu, Link2, Search, Truck } from "@/components/icons";
import { Card, Empty, Stats, Tag, Tile } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { formatAgo } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import { usePoll } from "@/lib/use-poll";

const TRIPS = moduleById("trips")!;
type Row = Record<string, string>;

export default function MasterLookupPage() {
  const states = usePoll<Row[]>("/api/vehicles/states?limit=1000", 10000);
  const [query, setQuery] = useState("");
  const all = states.data ?? [];
  const resolved = all.filter((v) => v.vehicleRegistrationNo);
  const companies = new Set(resolved.map((v) => v.tenantCode).filter(Boolean));
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return all
      .filter((v) => !q || `${v.imei} ${v.vehicleRegistrationNo} ${v.tenantCode}`.toLowerCase().includes(q))
      .sort((a, b) => (a.tenantCode || "~").localeCompare(b.tenantCode || "~") || (a.vehicleRegistrationNo || "").localeCompare(b.vehicleRegistrationNo || ""));
  }, [all, query]);

  return (
    <div className="stack">
      <ModuleHeader
        module={TRIPS}
        title="Master table lookup"
        description="A device only knows its IMEI. Before a trip is opened, the IMEI is looked up in the master tables to find which vehicle the device is fitted to at that moment, and which company owns it."
      />

      <Card title="The lookup" subtitle="device → device_assignment (valid at the reading's time) → vehicle → tenant">
        <div className="resolve">
          <div className="resolve-step">
            <Cpu size={18} />
            <div>
              <div className="resolve-name">device</div>
              <div className="mono faint">imei</div>
            </div>
          </div>
          <ArrowRight size={16} className="resolve-arrow" />
          <div className="resolve-step">
            <Link2 size={18} />
            <div>
              <div className="resolve-name">device_assignment</div>
              <div className="mono faint">from ≤ t &lt; to</div>
            </div>
          </div>
          <ArrowRight size={16} className="resolve-arrow" />
          <div className="resolve-step">
            <Truck size={18} />
            <div>
              <div className="resolve-name">vehicle</div>
              <div className="mono faint">registration_no</div>
            </div>
          </div>
          <ArrowRight size={16} className="resolve-arrow" />
          <div className="resolve-step">
            <Building2 size={18} />
            <div>
              <div className="resolve-name">tenant</div>
              <div className="mono faint">tenant_code</div>
            </div>
          </div>
        </div>
      </Card>

      <Stats>
        <Tile label="Devices reporting" value={states.data ? all.length : undefined} big />
        <Tile label="Resolved to a vehicle" value={states.data ? resolved.length : undefined} tone="good" big />
        <Tile label="Unassigned devices" value={states.data ? all.length - resolved.length : undefined} tone={all.length - resolved.length ? "warn" : undefined} big foot="no assignment at that time" />
        <Tile label="Companies" value={states.data ? companies.size : undefined} big />
      </Stats>

      <Card icon={Link2} title="Resolved assignments" subtitle="As seen by the processors (from the vehicle state of each device)." live flush>
        <div className="table-toolbar">
          <label className="search-field">
            <Search size={15} />
            <input type="search" placeholder="IMEI, vehicle or company" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Filter" />
          </label>
        </div>
        {!states.data ? (
          <Empty>Waiting for the service.</Empty>
        ) : rows.length === 0 ? (
          <Empty>No devices yet.</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>IMEI</th>
                  <th>Vehicle</th>
                  <th>Company</th>
                  <th>Lookup</th>
                  <th>Last reading</th>
                </tr>
              </thead>
              <tbody>
                {rows.slice(0, 80).map((v) => (
                  <tr key={v.imei}>
                    <td className="mono muted">{v.imei}</td>
                    <td className="mono">{v.vehicleRegistrationNo || "–"}</td>
                    <td>{v.tenantCode || "–"}</td>
                    <td>{v.vehicleRegistrationNo ? <Tag tone="good">RESOLVED</Tag> : <Tag tone="warn">UNASSIGNED</Tag>}</td>
                    <td className="muted">{formatAgo(v.lastRecordedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
