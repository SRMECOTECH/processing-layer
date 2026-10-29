"use client";

import { useState } from "react";
import { KeyRound, Search } from "@/components/icons";
import { Card, Code, Empty, Tag } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { formatNumber } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { VehicleTrackingState } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const STATE = moduleById("state")!;

/** The key list of RedisKeyNames, with the consumer that writes each key. */
const KEYS: [string, string, string, string][] = [
  ["gps:live:vehicle:{imei}", "hash", "Live location", "Latest position: vehicle, lat, lon, speed, status, times"],
  ["gps:live:positions", "geo", "Live location", "Every device's latest point, for “who is near X?” and geofencing"],
  ["gps:state:vehicle:{imei}", "hash", "Vehicle state", "Position, heading, acceleration, odometer, Kalman filter, counters"],
  ["gps:history:{imei}", "stream", "Vehicle state", "Recent readings (capped): the historical context"],
  ["gps:state:speed:{imei}", "hash", "Speed violation", "Phase, episode start, ping count, max speed, verification"],
  ["gps:state:ignition:{imei}", "hash", "Ignition", "On/off, source, engine seconds, idling"],
  ["gps:state:stoppage:{imei}", "hash", "Stoppage", "Phase, anchor, centroid, start, classification"],
  ["gps:events:{imei}", "list", "All processors", "The vehicle's latest events, newest first (capped)"],
  ["gps:config:speed-rule:{scope}", "hash", "Configuration", "Speed rule; scope = default | tenant:{code} | vehicle:{registration}"],
  ["gps:config:speed-rules", "set", "Configuration", "Every scope that has a rule"],
  ["gps:context:weather:{cell}", "string", "Stoppage agents", "Cached weather for a 0.1° cell (expires)"],
  ["gps:engine:stage:{stage}", "hash", "Engine", "Load of one producer or consumer"],
];

export default function KeyLayoutPage() {
  const [imei, setImei] = useState("");
  const [lookup, setLookup] = useState("");
  const state = usePoll<VehicleTrackingState>(lookup ? `/api/vehicles/${encodeURIComponent(lookup)}/state?history=1` : null, 5000);

  return (
    <div className="stack">
      <ModuleHeader
        module={STATE}
        title="Key layout"
        description="Every Redis key the platform writes, which processor owns it and what it holds. Open RedisInsight (localhost:8001) and look for “gps:”."
      />

      <Card icon={KeyRound} title="Keys" subtitle="From RedisKeyNames: the single list of Redis keys." flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Type</th>
                <th>Written by</th>
                <th>Holds</th>
              </tr>
            </thead>
            <tbody>
              {KEYS.map(([key, type, owner, holds]) => (
                <tr key={key}>
                  <td>
                    <Code>{key}</Code>
                  </td>
                  <td>
                    <Tag tone="neutral">{type.toUpperCase()}</Tag>
                  </td>
                  <td className="muted">{owner}</td>
                  <td className="muted wrap">{holds}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card
        icon={Search}
        title="Keys of one vehicle"
        subtitle="Which of the keys exist for an IMEI right now."
        live
        actions={
          <form
            className="toolbar-row"
            onSubmit={(e) => {
              e.preventDefault();
              setLookup(imei.trim());
            }}
          >
            <input className="input-box mono" placeholder="IMEI" value={imei} onChange={(e) => setImei(e.target.value)} aria-label="IMEI" />
            <button type="submit" className="btn soft" disabled={!imei.trim()}>
              Look up
            </button>
          </form>
        }
      >
        {!lookup ? (
          <Empty>Type an IMEI to see its keys.</Empty>
        ) : !state.data ? (
          <Empty>{state.error ?? "Looking up."}</Empty>
        ) : (
          <ul className="row-list">
            {state.data.cache.map((c) => {
              const size = Object.keys(c.value ?? {}).length;
              return (
                <li key={c.key} className="row-static">
                  <span className={`status-dot ${c.type === "none" ? "muted" : "good"}`} />
                  <span className="row-main">
                    <Code>{c.key}</Code>
                    <span className="row-note">{c.purpose}</span>
                  </span>
                  <span className="row-side mono">
                    {c.type === "none" ? "missing" : c.type === "hash" ? `${formatNumber(size)} fields` : `${formatNumber(Number(c.value.length ?? 0))} entries`}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </div>
  );
}
