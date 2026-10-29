"use client";

import { useState, type ReactNode } from "react";
import { Database, ExternalLink, Route, Send } from "@/components/icons";
import { Callout, Card, KeyValues, Segmented, Tag } from "@/components/ops/kit";
import { formatAgo, formatBytes, formatDateTime, formatNumber } from "@/lib/format";
import type {
  ConnectionsOverview,
  RedisCheck,
  RedisChoice,
  RedisSwitch,
  RedisView,
  ValhallaCheck,
  ValhallaView,
} from "@/lib/types";
import { sendJson } from "@/lib/use-poll";

type Message = { tone: "info" | "warn"; text: ReactNode };

/** A round trip in whole milliseconds; a local Redis answers in less than one. */
function roundTrip(millis: number | null): string {
  return millis === null ? "–" : millis < 1 ? "< 1 ms" : `${millis} ms`;
}

/** "Answers · 3 ms" or "No answer", next to a connection's title. */
function Answers({ ok, millis }: { ok: boolean; millis: number | null }) {
  return ok ? <Tag tone="good">ANSWERS{millis !== null ? ` · ${roundTrip(millis).toUpperCase()}` : ""}</Tag> : <Tag tone="bad">NO ANSWER</Tag>;
}

function Warnings({ items }: { items: string[] }) {
  if (items.length === 0) return null;
  return (
    <Callout tone="warn" title="Think twice">
      <ul className="cn-list">
        {items.map((w) => (
          <li key={w}>{w}</li>
        ))}
      </ul>
    </Callout>
  );
}

/** What runs where: the answer to "which part is the ingestion API?". */
export function WhereThingsRun({ data }: { data: ConnectionsOverview }) {
  return (
    <Card title="Where things run" subtitle="This laptop holds the pipeline; the HP home server holds the map and its own small API">
      <KeyValues
        rows={[
          ["This laptop", "Simulator → Kafka → Kafka Streams → consumers → PostgreSQL. The consumers keep their state in Redis (below)."],
          [
            "HP home server",
            <>
              Reached over its hotspot <b>SANJOY-HP 0161</b>, always 192.168.137.1: Valhalla, Redis and the ingestion API.
            </>,
          ],
          [
            "Ingestion API",
            <>
              The HP's own entry point for devices (<span className="mono">{data.ingestionApi.url}</span>). It writes into the HP's
              Redis, not into our Kafka, so it is only checked here.
            </>,
          ],
        ]}
      />
    </Card>
  );
}

// ---- Redis ----

type RedisAddress = { host: string; port: string; database: string };

function parseRedis(url: string): RedisAddress {
  const match = /^redis:\/\/([^:/]+)(?::(\d+))?(?:\/(\d+))?\/?$/.exec(url.trim());
  return match ? { host: match[1], port: match[2] ?? "6379", database: match[3] ?? "0" } : { host: "", port: "6379", database: "0" };
}

function redisUrl(address: RedisAddress): string {
  return `redis://${address.host.trim()}:${address.port.trim() || "6379"}/${address.database.trim() || "0"}`;
}

function RedisFacts({ check, since }: { check: RedisCheck; since?: string }) {
  const rows: [ReactNode, ReactNode][] = [["Address", <span className="mono">{check.url}</span>]];
  if (since) rows.push(["In use since", `${formatDateTime(since)} (${formatAgo(since)})`]);
  if (check.reachable) {
    rows.push(
      ["Round trip", roundTrip(check.millis)],
      ["Version", `Redis ${check.version ?? "?"}`],
      ["Memory", `${formatBytes(check.usedMemoryBytes)} used${check.maxMemoryBytes ? ` of ${formatBytes(check.maxMemoryBytes)}` : ", no cap"}`],
      [
        "When full",
        check.evictionPolicy === "noeviction" ? (
          <Tag tone="good">NOEVICTION · NEVER DELETES</Tag>
        ) : (
          <Tag tone="warn">{`${(check.evictionPolicy ?? "?").toUpperCase()} · DELETES KEYS`}</Tag>
        ),
      ],
      ["Keys in this database", formatNumber(check.keys)],
      ["Speed rules", check.hasPlatformConfiguration ? "Present" : "None yet: the default rule is created on switching"],
    );
    if (check.otherProgramsKeys.length > 0) {
      rows.push(["Also here", <span className="mono">{`HP ingestion API: ${check.otherProgramsKeys.join(", ")}`}</span>]);
    }
  }
  return <KeyValues rows={rows} />;
}

const REDIS_CHOICES: [RedisChoice, string][] = [
  ["local", "This laptop"],
  ["home-server", "HP home server"],
  ["custom", "Custom"],
];

/** The Redis every consumer uses, and switching it while the service runs (PUT /api/connections/redis). */
export function RedisCard({ view, onChanged }: { view: RedisView; onChanged: () => void }) {
  const [choice, setChoice] = useState<RedisChoice>(view.choice);
  const [address, setAddress] = useState<RedisAddress>(() => parseRedis(view.url));
  const [copy, setCopy] = useState(true);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [test, setTest] = useState<RedisCheck>();
  const [message, setMessage] = useState<Message>();

  const target = redisUrl(address);
  const inUse = target === view.url;
  const status = view.status;

  function pick(next: RedisChoice) {
    setChoice(next);
    setTest(undefined);
    setConfirming(false);
    if (next === "local") setAddress(parseRedis(view.localUrl));
    if (next === "home-server") setAddress(parseRedis(view.homeServerUrl));
  }

  function edit(field: keyof RedisAddress, value: string) {
    setAddress({ ...address, [field]: value });
    setTest(undefined);
    setConfirming(false);
  }

  async function runTest() {
    setBusy(true);
    setMessage(undefined);
    try {
      setTest(await sendJson<RedisCheck>("/api/connections/redis/test", "POST", { url: target }));
    } catch (error) {
      setMessage({ tone: "warn", text: error instanceof Error ? error.message : "The test failed" });
    } finally {
      setBusy(false);
    }
  }

  async function runSwitch() {
    setBusy(true);
    setMessage(undefined);
    try {
      const result = await sendJson<RedisSwitch>("/api/connections/redis", "PUT", { url: target, copyConfiguration: copy });
      setTest(undefined);
      setMessage({
        tone: "info",
        text: (
          <>
            Now using <span className="mono">{result.to}</span>. {result.configurationKeysCopied} configuration keys copied; vehicle state
            builds up again from the next readings.
            {result.notes.length > 0 && (
              <ul className="cn-list">
                {result.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            )}
          </>
        ),
      });
      onChanged();
    } catch (error) {
      setMessage({ tone: "warn", text: error instanceof Error ? error.message : "The switch failed" });
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  return (
    <Card
      icon={Database}
      title="Redis"
      subtitle="The consumers' working memory: live positions, vehicle state, episodes, history, speed rules"
      badges={<Answers ok={status.reachable} millis={status.millis} />}
      live
    >
      <div className="cn-body">
        <RedisFacts check={status} since={view.since} />
        {!status.reachable && (
          <Callout tone="warn" title="The Redis in use does not answer">
            {status.problem} The consumers wait and retry; Kafka keeps every reading until Redis is back.
          </Callout>
        )}
        <Warnings items={status.warnings} />

        <div className="cn-section">
          <div className="cn-section-title">Use another Redis</div>
          <Segmented options={REDIS_CHOICES} value={choice} onChange={pick} label="Redis to use" />
          <div className="form-grid">
            <label className="field">
              <span className="field-label">Host</span>
              <input className="input-box mono" value={address.host} disabled={choice !== "custom"} onChange={(e) => edit("host", e.target.value)} />
            </label>
            <label className="field">
              <span className="field-label">Port</span>
              <input
                className="input-box mono"
                inputMode="numeric"
                value={address.port}
                disabled={choice !== "custom"}
                onChange={(e) => edit("port", e.target.value)}
              />
            </label>
            <label className="field">
              <span className="field-label">Database</span>
              <input
                className="input-box mono"
                inputMode="numeric"
                value={address.database}
                disabled={choice !== "custom"}
                onChange={(e) => edit("database", e.target.value)}
              />
              <span className="field-hint">On the HP use 1: its ingestion API owns 0</span>
            </label>
          </div>
          <label className="check cn-check">
            <input type="checkbox" checked={copy} onChange={(e) => setCopy(e.target.checked)} />
            Copy the speed rules (gps:config:*) to the new Redis, without overwriting what is there
          </label>
          <div className="card-actions">
            <button type="button" className="btn soft" disabled={busy} onClick={runTest}>
              Test {target}
            </button>
            {inUse ? (
              <span className="muted">In use now</span>
            ) : confirming ? (
              <>
                <button type="button" className="btn primary" disabled={busy} onClick={runSwitch}>
                  Switch now
                </button>
                <button type="button" className="btn soft" disabled={busy} onClick={() => setConfirming(false)}>
                  Cancel
                </button>
                <span className="muted">Every consumer moves at once; vehicle state starts again from the next readings.</span>
              </>
            ) : (
              <button type="button" className="btn primary" disabled={busy} onClick={() => setConfirming(true)}>
                Use this Redis
              </button>
            )}
          </div>
          {test && (
            <div className="cn-result">
              <div className="cn-result-head">
                <span className="cn-section-title">Test result</span>
                <Answers ok={test.reachable} millis={test.millis} />
              </div>
              {test.reachable ? <RedisFacts check={test} /> : <p className="muted">{test.problem}</p>}
              <Warnings items={test.warnings} />
            </div>
          )}
          {message && <Callout tone={message.tone}>{message.text}</Callout>}
        </div>
      </div>
    </Card>
  );
}

// ---- Valhalla ----

function ValhallaFacts({ check }: { check: ValhallaCheck }) {
  const rows: [ReactNode, ReactNode][] = [["Address", <span className="mono">{check.url}</span>]];
  if (check.reachable) {
    rows.push(
      ["Round trip", `${roundTrip(check.millis)} (/status)`],
      ["Version", `Valhalla ${check.version ?? "?"}`],
      ["Map tiles built", formatDateTime(check.tilesBuilt)],
    );
    if (check.sampleRouteKm !== null) {
      rows.push([
        "Test route",
        `Kolkata, 2 points: ${check.sampleRouteKm.toFixed(1)} km, ${check.sampleRouteMinutes} min, answered in ${check.sampleRouteMillis} ms`,
      ]);
    }
    if (check.actions.length > 0) rows.push(["Offers", <span className="mono cn-actions">{check.actions.join(" · ")}</span>]);
  }
  return <KeyValues rows={rows} />;
}

/** The routing engine's address (PUT /api/connections/valhalla); route deviation will call it. */
export function ValhallaCard({ view, onChanged }: { view: ValhallaView; onChanged: () => void }) {
  const [url, setUrl] = useState(view.url);
  const [busy, setBusy] = useState(false);
  const [test, setTest] = useState<ValhallaCheck>();
  const [message, setMessage] = useState<Message>();
  const status = view.status;

  async function run<T>(work: () => Promise<T>, done: (result: T) => void) {
    setBusy(true);
    setMessage(undefined);
    try {
      done(await work());
    } catch (error) {
      setMessage({ tone: "warn", text: error instanceof Error ? error.message : "The request failed" });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card
      icon={Route}
      title="Valhalla routing"
      subtitle="All-India map on the HP: routes, map matching, distance matrices"
      badges={<Answers ok={status.reachable} millis={status.millis} />}
      live
    >
      <div className="cn-body">
        <ValhallaFacts check={status} />
        {!status.reachable && (
          <Callout tone="warn" title="Valhalla does not answer">
            {status.problem}
            <br />
            On the HP: <span className="mono">wsl -d Ubuntu-24.04 -u root -- docker ps</span>
          </Callout>
        )}

        <div className="cn-section">
          <div className="cn-section-title">Address</div>
          <input
            className="input-box mono"
            value={url}
            aria-label="Valhalla address"
            onChange={(e) => {
              setUrl(e.target.value);
              setTest(undefined);
            }}
          />
          <div className="card-actions">
            <button
              type="button"
              className="btn soft"
              disabled={busy}
              onClick={() => run(() => sendJson<ValhallaCheck>("/api/connections/valhalla/test", "POST", { url }), setTest)}
            >
              Test with a route
            </button>
            <button
              type="button"
              className="btn primary"
              disabled={busy || url.trim().replace(/\/+$/, "") === view.url}
              onClick={() =>
                run(
                  () => sendJson<ValhallaView>("/api/connections/valhalla", "PUT", { url }),
                  (saved) => {
                    setUrl(saved.url);
                    setMessage({ tone: "info", text: `Saved. The service calls Valhalla at ${saved.url} from now on.` });
                    onChanged();
                  },
                )
              }
            >
              Save
            </button>
            {url !== view.defaultUrl && (
              <button type="button" className="btn soft" disabled={busy} onClick={() => setUrl(view.defaultUrl)}>
                Default ({view.defaultUrl})
              </button>
            )}
          </div>
          {test && (
            <div className="cn-result">
              <div className="cn-result-head">
                <span className="cn-section-title">Test result</span>
                <Answers ok={test.reachable && !test.problem} millis={test.millis} />
              </div>
              {test.reachable ? <ValhallaFacts check={test} /> : null}
              {test.problem && <p className="muted">{test.problem}</p>}
            </div>
          )}
          {message && <Callout tone={message.tone}>{message.text}</Callout>}
        </div>
      </div>
    </Card>
  );
}

// ---- ingestion API (read-only) ----

export function IngestionApiCard({ data, onCheck }: { data: ConnectionsOverview["ingestionApi"]; onCheck: () => void }) {
  const status = data.status;
  const health = Object.entries(status.health ?? {});
  return (
    <Card
      icon={Send}
      title="Ingestion API (HP)"
      subtitle="FastAPI on the HP · read-only here"
      badges={
        <>
          <Tag tone="neutral">READ-ONLY</Tag>
          <Answers ok={status.reachable} millis={status.millis} />
        </>
      }
      live
    >
      <div className="cn-body">
        <p className="muted">
          Devices post points to it (<span className="mono">POST /ingest</span>, <span className="mono">/ingest/batch</span>) and it keeps them
          in the HP&apos;s Redis (<span className="mono">gps:points</span>, <span className="mono">last:*</span>,{" "}
          <span className="mono">track:*</span>). Our simulator does not go through it: it writes to Kafka on this laptop.
        </p>
        <KeyValues
          rows={[
            ["Address", <span className="mono">{data.url}</span>],
            ...(status.reachable
              ? ([
                  ["Round trip", `${roundTrip(status.millis)} (/health)`],
                  [
                    "Its health",
                    <span className="cn-tags">
                      {health.map(([name, ok]) => (
                        <Tag key={name} tone={ok === true ? "good" : "bad"}>
                          {`${name.toUpperCase()} ${ok === true ? "OK" : "DOWN"}`}
                        </Tag>
                      ))}
                    </span>,
                  ],
                ] as [ReactNode, ReactNode][])
              : []),
          ]}
        />
        {!status.reachable && <Callout tone="warn">{status.problem}</Callout>}
        <div className="card-actions">
          <button type="button" className="btn soft" onClick={onCheck}>
            Check now
          </button>
          <a className="btn soft" href={`${data.url}/docs`} target="_blank" rel="noreferrer">
            <ExternalLink size={15} />
            Swagger docs
          </a>
        </div>
      </div>
    </Card>
  );
}
