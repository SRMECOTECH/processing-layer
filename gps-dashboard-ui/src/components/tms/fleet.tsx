"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Map, Search, Siren, Truck } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { CsvButton } from "@/components/ops/reports";
import { formatAgo, formatNumber } from "@/lib/format";
import { FLEET_STATE_LABEL, STAGE_LABEL, fleetTone, scoped, type FleetRow, type FleetState } from "@/lib/tms";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import { shipmentHref, useViewer } from "@/lib/viewer";
import { FleetMap } from "./maps";
import { CompanyPicker, useCompanyScope } from "./shipments";

const STATES: (FleetState | "ALL")[] = ["ALL", "MOVING", "STANDING", "OFFLINE", "NEVER_REPORTED", "NO_DEVICE"];

/** Every truck of the transporter (its own and those carrying its shipments), where it is and what it carries. */
export function FleetBoard() {
  const viewer = useViewer();
  const scope = useCompanyScope();
  const poll = usePoll<FleetRow[]>(scoped("/api/portal/fleet", scope), 10_000);
  const [state, setState] = useState<FleetState | "ALL">("ALL");
  const [q, setQ] = useState("");
  const rows = useMemo(
    () =>
      (poll.data ?? []).filter(
        (r) => (state === "ALL" || r.state === state) && (!q.trim() || `${r.vehicleNo} ${r.imei ?? ""} ${r.ownerName ?? ""}`.toLowerCase().includes(q.trim().toLowerCase())),
      ),
    [poll.data, state, q],
  );
  const count = (s: FleetState) => (poll.data ?? []).filter((r) => r.state === s).length;
  const now = Date.now();

  return (
    <div className="stack">
      <PageHeader
        title="Fleet board"
        description="Your own trucks and the trucks carrying your shipments this month, with their GPS device and what they are doing now."
        actions={viewer.role === "DEVELOPER" ? <CompanyPicker kinds={["TRANSPORTER"]} /> : undefined}
      />
      <Stats columns={5}>
        <Tile label="Trucks" icon={Truck} value={poll.data?.length} big />
        <Tile label="Moving" value={count("MOVING")} tone="good" />
        <Tile label="Standing" value={count("STANDING")} />
        <Tile label="Offline" value={count("OFFLINE") + count("NEVER_REPORTED")} tone={count("OFFLINE") > 0 ? "warn" : undefined} foot="no reading for 30 min, or never" />
        <Tile label="No GPS device" value={count("NO_DEVICE")} tone={count("NO_DEVICE") > 0 ? "bad" : undefined} foot="ask the GPS provider to map one" />
      </Stats>
      <div className="grid-main-side">
        <Card
          icon={Truck}
          title="Trucks"
          subtitle={`${rows.length} shown`}
          live={!poll.error}
          flush
          actions={
            <CsvButton
              name="fleet"
              rows={() => [
                ["Vehicle", "Owner", "IMEI", "State", "Last reading", "Speed", "Trip", "Destination", "Km today"],
                ...rows.map((r) => [r.vehicleNo, r.ownerName ?? "", r.imei ?? "", r.state, r.lastReadingAt ?? "", r.speedKmph ?? "", r.shipment?.tripNo ?? "", r.shipment?.destination ?? "", r.today?.km ?? ""]),
              ]}
            />
          }
        >
          <div className="table-toolbar">
            <div className="chip-row">
              {STATES.map((s) => (
                <button key={s} type="button" className="chip" aria-pressed={state === s} onClick={() => setState(s)}>
                  {s === "ALL" ? "All" : FLEET_STATE_LABEL[s]}
                </button>
              ))}
            </div>
            <label className="search-field">
              <Search size={14} />
              <input placeholder="Vehicle, IMEI, owner" value={q} onChange={(e) => setQ(e.target.value)} />
            </label>
          </div>
          {!poll.data ? (
            <Empty>{poll.error ?? "Loading the fleet."}</Empty>
          ) : rows.length === 0 ? (
            <Empty>No truck matches.</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Vehicle</th>
                    <th>GPS device</th>
                    <th>State</th>
                    <th>Last reading</th>
                    <th className="right">Speed</th>
                    <th>Carrying</th>
                    <th className="right">Km today</th>
                    <th className="right">Over-speed today</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.vehicleNo}>
                      <td className="mono">
                        {r.vehicleNo}
                        {r.ownerName && <div className="faint" style={{ fontSize: 11 }}>{r.ownerName}</div>}
                      </td>
                      <td className="mono">{r.imei ?? "–"}</td>
                      <td>
                        <Tag tone={fleetTone(r.state)}>{FLEET_STATE_LABEL[r.state]}</Tag>
                      </td>
                      <td className="muted">{r.lastReadingAt ? formatAgo(r.lastReadingAt, now) : "–"}</td>
                      <td className="right mono">{r.speedKmph === null ? "–" : `${formatNumber(r.speedKmph)} km/h`}</td>
                      <td>
                        {r.shipment ? (
                          <Link className="link" href={shipmentHref(viewer.role, r.shipment.id)}>
                            {r.shipment.tripNo} → {r.shipment.destination} · {STAGE_LABEL[r.shipment.stage]}
                          </Link>
                        ) : (
                          <span className="faint">free</span>
                        )}
                      </td>
                      <td className="right mono">{r.today ? r.today.km : "–"}</td>
                      <td className="right mono">{r.today ? r.today.overspeedEvents : "–"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
        <Card icon={Map} title="Where they are" subtitle="Latest position of every truck">
          <FleetMap rows={rows} />
        </Card>
      </div>
    </div>
  );
}

/** The alerts a viewer may see: its trucks, and trucks while they carry its shipments. */
export function AlertsPage({ description }: { description: string }) {
  const viewer = useViewer();
  const scope = useCompanyScope();
  const poll = usePoll<VehicleEventView[]>(scoped("/api/portal/alerts?limit=300", scope), 15_000);
  const [only, setOnly] = useState<"ALL" | "SERIOUS" | "SHIPMENT">("SERIOUS");
  const rows = (poll.data ?? []).filter((e) =>
    only === "ALL" ? true : only === "SHIPMENT" ? e.type.startsWith("SHIPMENT_") : e.severity === "HIGH" || e.severity === "CRITICAL" || e.severity === "MEDIUM",
  );
  return (
    <div className="stack">
      <PageHeader
        title="Alerts"
        description={description}
        actions={viewer.role === "DEVELOPER" ? <CompanyPicker kinds={["TRANSPORTER", "CONSIGNER"]} /> : undefined}
      />
      <Card icon={Siren} title="Last 7 days" subtitle={poll.data ? `${rows.length} of ${poll.data.length}` : "Loading"} live={!poll.error} flush>
        <div className="table-toolbar">
          <div className="chip-row">
            {(
              [
                ["SERIOUS", "Medium and above"],
                ["SHIPMENT", "Shipment departures, arrivals, delays"],
                ["ALL", "Everything"],
              ] as const
            ).map(([key, label]) => (
              <button key={key} type="button" className="chip" aria-pressed={only === key} onClick={() => setOnly(key)}>
                {label}
              </button>
            ))}
          </div>
        </div>
        <EventTable events={poll.data ? rows : undefined} empty="No alerts." />
      </Card>
    </div>
  );
}
