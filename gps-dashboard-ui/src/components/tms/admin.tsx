"use client";

import { useMemo, useState } from "react";
import { Building2, History, Link2, MapPinned, Plus, Radio, Truck, Users } from "@/components/icons";
import { Callout, Card, Empty, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo, formatDateTime } from "@/lib/format";
import type { Company } from "@/lib/tms";
import { sendJson, usePoll } from "@/lib/use-poll";
import s from "./tms.module.css";

type DeviceRow = {
  imei: string;
  kind: string;
  protocol: string | null;
  vehicleRegistrationNo: string | null;
  ownerCode: string | null;
  ownerName: string | null;
  fittedSince: string | null;
  lastReadingAt: string | null;
};
type VehicleRow = { registrationNo: string; ownerCode: string | null; ownerName: string | null; imei: string | null; fittedSince: string | null };
type AssignmentRow = { id: string; imei: string; vehicleRegistrationNo: string; validFrom: string; validTo: string | null };
type Change = { outcome: string; assignment: AssignmentRow | null; closed: AssignmentRow[] };

/** A small "busy / message" helper for the admin forms. */
function useAction() {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ tone: "info" | "warn"; text: string } | null>(null);
  const run = async (work: () => Promise<string>) => {
    setBusy(true);
    setMessage(null);
    try {
      setMessage({ tone: "info", text: await work() });
    } catch (problem) {
      setMessage({ tone: "warn", text: problem instanceof Error ? problem.message : "It did not work" });
    } finally {
      setBusy(false);
    }
  };
  return { busy, message, run };
}

function Message({ message }: { message: { tone: "info" | "warn"; text: string } | null }) {
  return message ? <Callout tone={message.tone}>{message.text}</Callout> : null;
}

/** A select with a filter box above it: a fleet has hundreds of IMEIs and trucks. */
function FilteredSelect({ label, options, value, onChange, placeholder }: { label: string; options: [string, string][]; value: string; onChange: (v: string) => void; placeholder: string }) {
  const [filter, setFilter] = useState("");
  const shown = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return f ? options.filter(([v, text]) => `${v} ${text}`.toLowerCase().includes(f)) : options;
  }, [options, filter]);
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      <input className="input-box mono" placeholder="Type to filter" value={filter} onChange={(e) => setFilter(e.target.value)} />
      <select className="select mono" value={value} onChange={(e) => onChange(e.target.value)} size={1}>
        <option value="">{placeholder}</option>
        {shown.slice(0, 500).map(([v, text]) => (
          <option key={v} value={v}>
            {text}
          </option>
        ))}
      </select>
      <span className="field-hint">{shown.length} of {options.length}</span>
    </label>
  );
}

/**
 * Developer -> TMS -> Device ↔ vehicle mapping: which GPS device (IMEI) is fitted in which truck, from when. The two
 * drop-downs are the mapping the GPS provider and the transporter agree on; history is kept as periods, so a late
 * reading still finds the truck the device was in at the time.
 */
export function MappingPage() {
  const devices = usePoll<DeviceRow[]>("/api/portal/admin/devices", 15_000);
  const vehicles = usePoll<VehicleRow[]>("/api/portal/admin/vehicles", 15_000);
  const [imei, setImei] = useState("");
  const [vehicle, setVehicle] = useState("");
  const [from, setFrom] = useState("");
  const [newImei, setNewImei] = useState("");
  const [newVehicle, setNewVehicle] = useState("");
  const [historyFor, setHistoryFor] = useState<string | null>(null);
  const history = usePoll<AssignmentRow[]>(historyFor ? `/api/portal/admin/mappings/history?imei=${encodeURIComponent(historyFor)}` : null, 30_000);
  const action = useAction();

  const refresh = () => {
    devices.refresh();
    vehicles.refresh();
    history.refresh();
  };

  const deviceOptions = useMemo<[string, string][]>(
    () => (devices.data ?? []).map((d) => [d.imei, `${d.imei}${d.vehicleRegistrationNo ? `  ·  in ${d.vehicleRegistrationNo}` : "  ·  free"}`]),
    [devices.data],
  );
  const vehicleOptions = useMemo<[string, string][]>(
    () => (vehicles.data ?? []).map((v) => [v.registrationNo, `${v.registrationNo}${v.imei ? `  ·  has ${v.imei}` : "  ·  no device"}${v.ownerName ? `  ·  ${v.ownerName}` : ""}`]),
    [vehicles.data],
  );
  const mapped = (devices.data ?? []).filter((d) => d.vehicleRegistrationNo);
  const freeDevices = (devices.data ?? []).length - mapped.length;
  const trucksWithout = (vehicles.data ?? []).filter((v) => !v.imei).length;

  const assign = () =>
    action.run(async () => {
      const change = await sendJson<Change>("/api/portal/admin/mappings", "POST", { imei, vehicleNo: vehicle, from: from ? new Date(from).toISOString() : null });
      refresh();
      return change.outcome === "UNCHANGED"
        ? `${imei} is already fitted in ${vehicle}: nothing changed.`
        : `${imei} fitted in ${vehicle} from ${formatDateTime(change.assignment?.validFrom)}${change.closed.length ? `; closed ${change.closed.map((c) => `${c.imei} in ${c.vehicleRegistrationNo}`).join(", ")}` : ""}.`;
    });

  const unassign = (device: string) =>
    action.run(async () => {
      await sendJson<Change>("/api/portal/admin/mappings/unassign", "POST", { imei: device, vehicleNo: null, from: null });
      refresh();
      return `${device} taken out of its truck.`;
    });

  return (
    <div className="stack">
      <PageHeader
        title="Device ↔ vehicle mapping"
        description="Which GPS device is fitted in which truck, from when. Shipments are followed through this: a truck without a device is not tracked."
      />
      <Stats columns={4}>
        <Tile label="Devices" icon={Radio} value={devices.data?.length} foot={`${freeDevices} not in a truck`} />
        <Tile label="Mapped" icon={Link2} value={mapped.length} tone="good" />
        <Tile label="Trucks" icon={Truck} value={vehicles.data?.length} />
        <Tile label="Trucks without a device" value={trucksWithout} tone={trucksWithout > 0 ? "warn" : undefined} />
      </Stats>

      <div className="grid-2">
        <Card icon={Link2} title="Fit a device in a truck" subtitle="Moving a device closes its old mapping at that moment">
          <div className="stack">
            <div className="form-grid">
              <FilteredSelect label="GPS device (IMEI)" options={deviceOptions} value={imei} onChange={setImei} placeholder="Choose a device" />
              <FilteredSelect label="Vehicle number" options={vehicleOptions} value={vehicle} onChange={setVehicle} placeholder="Choose a truck" />
              <label className="field">
                <span className="field-label">Fitted from</span>
                <input className="input-box" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
                <span className="field-hint">Empty: now. Earlier: readings since then belong to this truck.</span>
              </label>
            </div>
            <Message message={action.message} />
            <div className={s.actions}>
              <button type="button" className="btn primary" disabled={action.busy || !imei || !vehicle} onClick={assign}>
                <Link2 size={14} /> Map
              </button>
            </div>
          </div>
        </Card>
        <Card icon={Plus} title="Add a device or a truck">
          <div className="form-grid">
            <label className="field">
              <span className="field-label">New device IMEI</span>
              <input className="input-box mono" value={newImei} onChange={(e) => setNewImei(e.target.value.replace(/\D/g, ""))} placeholder="0869925072822461" />
              <button
                type="button"
                className="btn soft"
                disabled={action.busy || newImei.length < 6}
                onClick={() =>
                  action.run(async () => {
                    await sendJson("/api/portal/admin/devices", "POST", { imei: newImei, kind: "GPS" });
                    setNewImei("");
                    refresh();
                    return `Device ${newImei} registered.`;
                  })
                }
              >
                Register device
              </button>
            </label>
            <label className="field">
              <span className="field-label">New vehicle number</span>
              <input className="input-box mono" value={newVehicle} onChange={(e) => setNewVehicle(e.target.value.toUpperCase())} placeholder="JH05CY2516" />
              <button
                type="button"
                className="btn soft"
                disabled={action.busy || newVehicle.trim().length < 4}
                onClick={() =>
                  action.run(async () => {
                    await sendJson("/api/portal/admin/vehicles", "POST", { registrationNo: newVehicle, ownerCode: null });
                    setNewVehicle("");
                    refresh();
                    return `Vehicle ${newVehicle} added.`;
                  })
                }
              >
                Add vehicle
              </button>
            </label>
          </div>
        </Card>
      </div>

      <div className="grid-main-side">
        <Card icon={Radio} title="Devices" subtitle="Each device, the truck it is in and its last reading" flush>
          {!devices.data ? (
            <Empty>{devices.error ?? "Loading."}</Empty>
          ) : (
            <div className="data-table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>IMEI</th>
                    <th>Truck</th>
                    <th>Owner</th>
                    <th>Since</th>
                    <th>Last reading</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {devices.data.map((d) => (
                    <tr key={d.imei}>
                      <td className="mono">
                        <button type="button" className="text-button mono" onClick={() => setHistoryFor(d.imei)}>
                          {d.imei}
                        </button>
                      </td>
                      <td className="mono">{d.vehicleRegistrationNo ?? <Tag tone="warn">free</Tag>}</td>
                      <td className={s.wrap}>{d.ownerName ?? "–"}</td>
                      <td className="muted">{d.fittedSince ? formatDateTime(d.fittedSince) : "–"}</td>
                      <td className="muted">{d.lastReadingAt ? formatAgo(d.lastReadingAt) : "never"}</td>
                      <td>
                        {d.vehicleRegistrationNo && (
                          <button type="button" className="btn danger-soft" disabled={action.busy} onClick={() => unassign(d.imei)}>
                            Take out
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
        <Card icon={History} title="History" subtitle={historyFor ? `Device ${historyFor}` : "Click an IMEI"} flush>
          {!historyFor ? (
            <Empty>Every period a device was fitted in a truck.</Empty>
          ) : !history.data ? (
            <Empty>Loading.</Empty>
          ) : history.data.length === 0 ? (
            <Empty>Never mapped.</Empty>
          ) : (
            <ul className="feed">
              {history.data.map((a) => (
                <li key={a.id} className="feed-row">
                  <span className={`status-dot ${a.validTo ? "muted" : "good"}`} />
                  <span className="feed-main">
                    <span className="feed-title mono">{a.vehicleRegistrationNo}</span>
                    <span className="feed-sub">
                      {formatDateTime(a.validFrom)} → {a.validTo ? formatDateTime(a.validTo) : "now"}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
}

type Account = {
  id: string;
  role: "DEVELOPER" | "TRANSPORTER" | "CONSIGNER";
  username: string;
  displayName: string;
  active: boolean;
  tenantCode: string | null;
  tenantName: string | null;
  createdAt: string;
  lastLoginAt: string | null;
};

const ROLE_LABEL = { DEVELOPER: "GPS provider", TRANSPORTER: "Transporter", CONSIGNER: "Consigner" } as const;

/** Developer -> TMS -> Companies & users: the transporters and consigners, and who of them may sign in. */
export function CompaniesPage() {
  const companies = usePoll<Company[]>("/api/portal/admin/companies", 30_000);
  const users = usePoll<Account[]>("/api/portal/admin/users", 30_000);
  const action = useAction();
  const [company, setCompany] = useState({ kind: "TRANSPORTER", name: "", externalCode: "" });
  const [user, setUser] = useState({ role: "TRANSPORTER", username: "", displayName: "", password: "", tenantCode: "" });
  const [kindFilter, setKindFilter] = useState<"ALL" | "TRANSPORTER" | "CONSIGNER">("ALL");
  const forRole = (companies.data ?? []).filter((c) => c.kind === user.role);
  const shown = (companies.data ?? []).filter((c) => c.kind !== "GPS_PROVIDER" && (kindFilter === "ALL" || c.kind === kindFilter));

  return (
    <div className="stack">
      <PageHeader
        title="Companies & users"
        description="Transporters and consigners on the platform, and their sign-ins. A transporter or consigner user only ever sees its own company's trucks and shipments."
      />
      <Message message={action.message} />
      <div className="grid-2">
        <Card icon={Building2} title="Add a company">
          <div className="form-grid">
            <label className="field">
              <span className="field-label">Kind</span>
              <select className="select" value={company.kind} onChange={(e) => setCompany({ ...company, kind: e.target.value })}>
                <option value="TRANSPORTER">Transporter (truck company)</option>
                <option value="CONSIGNER">Consigner (sends the goods)</option>
              </select>
            </label>
            <label className="field">
              <span className="field-label">Name</span>
              <input className="input-box" value={company.name} onChange={(e) => setCompany({ ...company, name: e.target.value })} placeholder="Virk Eenterprises" />
            </label>
            <label className="field">
              <span className="field-label">Code in the consigner&apos;s TMS</span>
              <input className="input-box mono" value={company.externalCode} onChange={(e) => setCompany({ ...company, externalCode: e.target.value })} placeholder="0000015300" />
              <span className="field-hint">Vendor code (i_trans_id) or consignor id (i_cnr_id)</span>
            </label>
          </div>
          <div className={s.actions} style={{ marginTop: 14 }}>
            <button
              type="button"
              className="btn primary"
              disabled={action.busy || !company.name.trim()}
              onClick={() =>
                action.run(async () => {
                  const c = await sendJson<Company>("/api/portal/admin/companies", "POST", company);
                  companies.refresh();
                  setCompany({ ...company, name: "", externalCode: "" });
                  return `${c.name} is on the platform (code ${c.code}).`;
                })
              }
            >
              Add company
            </button>
          </div>
        </Card>
        <Card icon={Users} title="Add a user">
          <div className="form-grid">
            <label className="field">
              <span className="field-label">Role</span>
              <select className="select" value={user.role} onChange={(e) => setUser({ ...user, role: e.target.value, tenantCode: "" })}>
                <option value="TRANSPORTER">Transporter</option>
                <option value="CONSIGNER">Consigner</option>
                <option value="DEVELOPER">GPS provider</option>
              </select>
            </label>
            {user.role !== "DEVELOPER" && (
              <label className="field">
                <span className="field-label">Company</span>
                <select className="select" value={user.tenantCode} onChange={(e) => setUser({ ...user, tenantCode: e.target.value })}>
                  <option value="">Choose</option>
                  {forRole.map((c) => (
                    <option key={c.code} value={c.code}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label className="field">
              <span className="field-label">User id</span>
              <input className="input-box mono" value={user.username} onChange={(e) => setUser({ ...user, username: e.target.value })} />
            </label>
            <label className="field">
              <span className="field-label">Name shown</span>
              <input className="input-box" value={user.displayName} onChange={(e) => setUser({ ...user, displayName: e.target.value })} />
            </label>
            <label className="field">
              <span className="field-label">Password</span>
              <input className="input-box" type="password" autoComplete="new-password" value={user.password} onChange={(e) => setUser({ ...user, password: e.target.value })} />
            </label>
          </div>
          <div className={s.actions} style={{ marginTop: 14 }}>
            <button
              type="button"
              className="btn primary"
              disabled={action.busy || !user.username.trim() || user.password.length < 3 || (user.role !== "DEVELOPER" && !user.tenantCode)}
              onClick={() =>
                action.run(async () => {
                  const a = await sendJson<Account>("/api/portal/admin/users", "POST", user);
                  users.refresh();
                  setUser({ ...user, username: "", displayName: "", password: "" });
                  return `${ROLE_LABEL[a.role]} user ${a.username} can sign in now.`;
                })
              }
            >
              Add user
            </button>
          </div>
        </Card>
      </div>

      <Card icon={Users} title="Users" subtitle={users.data ? `${users.data.length} sign-ins` : "Loading"} flush>
        {!users.data ? (
          <Empty>{users.error ?? "Loading."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Role</th>
                  <th>User id</th>
                  <th>Name</th>
                  <th>Company</th>
                  <th>Last sign-in</th>
                  <th>State</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {users.data.map((u) => (
                  <tr key={u.id}>
                    <td>
                      <Tag tone={u.role === "DEVELOPER" ? "ink" : u.role === "TRANSPORTER" ? "accent" : "good"}>{ROLE_LABEL[u.role]}</Tag>
                    </td>
                    <td className="mono">{u.username}</td>
                    <td>{u.displayName}</td>
                    <td className={s.wrap}>{u.tenantName ?? "–"}</td>
                    <td className="muted">{u.lastLoginAt ? formatAgo(u.lastLoginAt) : "never"}</td>
                    <td>{u.active ? <Tag tone="good">active</Tag> : <Tag tone="neutral">switched off</Tag>}</td>
                    <td>
                      <div className={s.actions}>
                        <button
                          type="button"
                          className="btn soft"
                          disabled={action.busy}
                          onClick={() => {
                            const password = window.prompt(`New password for ${u.username}`);
                            if (password) {
                              action.run(async () => {
                                await sendJson(`/api/portal/admin/users/${u.id}/password`, "POST", { password });
                                return `Password of ${u.username} changed.`;
                              });
                            }
                          }}
                        >
                          Password
                        </button>
                        <button
                          type="button"
                          className={u.active ? "btn danger-soft" : "btn soft"}
                          disabled={action.busy}
                          onClick={() =>
                            action.run(async () => {
                              await sendJson(`/api/portal/admin/users/${u.id}/active?value=${!u.active}`, "POST", {});
                              users.refresh();
                              return `${u.username} ${u.active ? "switched off" : "switched on"}.`;
                            })
                          }
                        >
                          {u.active ? "Switch off" : "Switch on"}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card
        icon={Building2}
        title="Companies"
        subtitle={`${shown.length} shown`}
        flush
        actions={
          <div className="chip-row">
            {(["ALL", "TRANSPORTER", "CONSIGNER"] as const).map((k) => (
              <button key={k} type="button" className="chip" aria-pressed={kindFilter === k} onClick={() => setKindFilter(k)}>
                {k === "ALL" ? "All" : k === "TRANSPORTER" ? "Transporters" : "Consigners"}
              </button>
            ))}
          </div>
        }
      >
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Kind</th>
                <th>Code</th>
                <th>TMS code</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((c) => (
                <tr key={c.code}>
                  <td className={s.wrap}>{c.name}</td>
                  <td>{c.kind === "TRANSPORTER" ? "Transporter" : "Consigner"}</td>
                  <td className="mono">{c.code}</td>
                  <td className="mono">{c.externalCode ?? "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}

type Place = { id: number; code: string; name: string; role: "ORIGIN" | "DESTINATION"; latitude: number; longitude: number; radiusMeters: number; source: string; active: boolean };

/** Developer -> TMS -> Places & geofences: the fences shipments start in and end in, matched to the files by name. */
export function PlacesPage() {
  const places = usePoll<Place[]>("/api/portal/admin/places", 60_000);
  const action = useAction();
  const [draft, setDraft] = useState({ name: "", role: "DESTINATION", latitude: "", longitude: "", radiusMeters: "1000" });
  const [q, setQ] = useState("");
  const shown = (places.data ?? []).filter((p) => p.active && (!q.trim() || p.code.includes(q.trim().toUpperCase())));
  return (
    <div className="stack">
      <PageHeader
        title="Places & geofences"
        description="A shipment file names its origin and destination in words (s_org_node_name, s_dest_node_name). The place with that name is its geofence: leaving the origin fence is the departure, entering the destination fence the arrival."
      />
      <Message message={action.message} />
      <Card icon={MapPinned} title="Add or change a place" subtitle="The name exactly as the shipment files write it">
        <div className="form-grid">
          <label className="field">
            <span className="field-label">Name</span>
            <input className="input-box" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="ADITYAPUR" />
          </label>
          <label className="field">
            <span className="field-label">Role</span>
            <select className="select" value={draft.role} onChange={(e) => setDraft({ ...draft, role: e.target.value })}>
              <option value="DESTINATION">Destination</option>
              <option value="ORIGIN">Origin (plant)</option>
            </select>
          </label>
          <label className="field">
            <span className="field-label">Latitude</span>
            <input className="input-box mono" value={draft.latitude} onChange={(e) => setDraft({ ...draft, latitude: e.target.value })} placeholder="22.796308" />
          </label>
          <label className="field">
            <span className="field-label">Longitude</span>
            <input className="input-box mono" value={draft.longitude} onChange={(e) => setDraft({ ...draft, longitude: e.target.value })} placeholder="86.137673" />
          </label>
          <label className="field">
            <span className="field-label">Radius (m)</span>
            <input className="input-box mono" value={draft.radiusMeters} onChange={(e) => setDraft({ ...draft, radiusMeters: e.target.value.replace(/\D/g, "") })} />
          </label>
        </div>
        <div className={s.actions} style={{ marginTop: 14 }}>
          <button
            type="button"
            className="btn primary"
            disabled={action.busy || !draft.name.trim() || !draft.latitude || !draft.longitude}
            onClick={() =>
              action.run(async () => {
                const p = await sendJson<Place>("/api/portal/admin/places", "POST", {
                  name: draft.name,
                  role: draft.role,
                  latitude: Number(draft.latitude),
                  longitude: Number(draft.longitude),
                  radiusMeters: Number(draft.radiusMeters),
                });
                places.refresh();
                return `${p.name} saved (${p.radiusMeters} m). Shipments imported from now on find it; re-upload a file to attach it to earlier ones.`;
              })
            }
          >
            Save place
          </button>
        </div>
      </Card>
      <Card
        icon={MapPinned}
        title="Places"
        subtitle={`${shown.length} active`}
        flush
        actions={<input className="input-box" placeholder="Filter" value={q} onChange={(e) => setQ(e.target.value)} />}
      >
        {!places.data ? (
          <Empty>{places.error ?? "Loading."}</Empty>
        ) : (
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Name in the files</th>
                  <th>Shown as</th>
                  <th>Role</th>
                  <th className="right">Position</th>
                  <th className="right">Radius</th>
                  <th>From</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {shown.map((p) => (
                  <tr key={p.id}>
                    <td className="mono">{p.code}</td>
                    <td>{p.name}</td>
                    <td>
                      <Tag tone={p.role === "ORIGIN" ? "warn" : "good"}>{p.role === "ORIGIN" ? "origin" : "destination"}</Tag>
                    </td>
                    <td className="right mono">
                      {p.latitude.toFixed(5)}, {p.longitude.toFixed(5)}
                    </td>
                    <td className="right mono">{p.radiusMeters >= 1000 ? `${(p.radiusMeters / 1000).toFixed(1)} km` : `${p.radiusMeters} m`}</td>
                    <td className="muted">{p.source === "SMART_TRUCK" ? "smart_truck" : "added here"}</td>
                    <td>
                      <button
                        type="button"
                        className="btn danger-soft"
                        disabled={action.busy}
                        onClick={() =>
                          action.run(async () => {
                            const r = await fetch(`/api/portal/admin/places/${p.id}`, { method: "DELETE" });
                            if (!r.ok) throw new Error("Not removed");
                            places.refresh();
                            return `${p.name} removed.`;
                          })
                        }
                      >
                        Remove
                      </button>
                    </td>
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

/** The demo: shipments behind the trips the fleet simulator is driving, from smart_truck. */
export function DemoShipmentsButton({ onDone }: { onDone?: () => void }) {
  const action = useAction();
  return (
    <div className="stack">
      <button
        type="button"
        className="btn soft"
        disabled={action.busy}
        onClick={() =>
          action.run(async () => {
            const r = await sendJson<{ created: number; updated: number; unchanged: number; rowsTotal: number }>("/api/portal/admin/demo-shipments", "POST", {});
            onDone?.();
            return `${r.rowsTotal} trips the simulator is driving: ${r.created} new, ${r.updated} changed, ${r.unchanged} unchanged shipments.`;
          })
        }
      >
        <Truck size={14} /> Load demo shipments from smart_truck
      </button>
      <Message message={action.message} />
    </div>
  );
}
