"use client";

import { useState } from "react";
import { Moon, Newspaper } from "@/components/icons";
import { Card, DemoButton, PageHeader, Segmented, Switch, Tag } from "@/components/ops/kit";

type Digest = "off" | "hourly" | "daily";

export default function QuietHoursPage() {
  const [quiet, setQuiet] = useState(true);
  const [from, setFrom] = useState("22:00");
  const [to, setTo] = useState("06:00");
  const [criticalThrough, setCriticalThrough] = useState(true);
  const [digest, setDigest] = useState<Digest>("daily");
  const [digestAt, setDigestAt] = useState("07:00");

  return (
    <div className="stack">
      <PageHeader
        title="Quiet hours & digest"
        description="When to hold notifications back, and whether to get a summary instead of one message per alert."
        badges={<Tag tone="neutral">PLANNED</Tag>}
        actions={<DemoButton primary>Save preferences</DemoButton>}
      />

      <div className="grid-2">
        <Card icon={Moon} title="Quiet hours" subtitle="Example settings; not saved yet." live={false}>
          <div className="switch-list">
            <label className="switch-row">
              <span>
                <span className="switch-title">Hold notifications at night</span>
                <span className="switch-sub">They wait in the inbox until quiet hours end</span>
              </span>
              <Switch checked={quiet} onChange={setQuiet} label="Quiet hours" />
            </label>
            <div className="time-range">
              <label className="field">
                <span className="field-label">From</span>
                <input className="input-box mono" type="time" value={from} onChange={(e) => setFrom(e.target.value)} disabled={!quiet} />
              </label>
              <label className="field">
                <span className="field-label">To</span>
                <input className="input-box mono" type="time" value={to} onChange={(e) => setTo(e.target.value)} disabled={!quiet} />
              </label>
            </div>
            <label className="switch-row">
              <span>
                <span className="switch-title">Critical alerts always get through</span>
                <span className="switch-sub">Route deviation, severe over-speed, platform down</span>
              </span>
              <Switch checked={criticalThrough} onChange={setCriticalThrough} label="Critical alerts bypass quiet hours" />
            </label>
          </div>
        </Card>

        <Card icon={Newspaper} title="Digest" subtitle="One summary instead of many messages." live={false}>
          <div className="switch-list">
            <div className="field">
              <span className="field-label">Send a digest</span>
              <Segmented<Digest>
                label="Digest"
                value={digest}
                onChange={setDigest}
                options={[
                  ["off", "Off"],
                  ["hourly", "Hourly"],
                  ["daily", "Daily"],
                ]}
              />
            </div>
            {digest === "daily" && (
              <label className="field">
                <span className="field-label">At</span>
                <input className="input-box mono" type="time" value={digestAt} onChange={(e) => setDigestAt(e.target.value)} />
              </label>
            )}
            <p className="muted small">
              The digest lists alerts by company and type, with the vehicles involved and links back to this console.
            </p>
          </div>
        </Card>
      </div>
    </div>
  );
}
