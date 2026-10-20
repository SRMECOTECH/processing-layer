"use client";

import Link from "next/link";
import { BellRing, RefreshCw, Siren, Webhook } from "lucide-react";
import { Card, DemoButton, Empty, PageTitle, Tag } from "@/components/ops/kit";
import { alertsFrom } from "@/lib/alerts";
import { useOverview } from "@/lib/overview-context";

export default function DispatchPage() {
  const { data } = useOverview();
  const alerts = alertsFrom(data);

  return (
    <div className="stack">
      <PageTitle
        title="Dispatch and escalations"
        subtitle="Where alerts go once a rule fires: operations desk, the driver's cab and the incident ledger. Platform alerts below are worked out live from the pipeline."
      />

      <Card icon={BellRing} title="Platform alerts" subtitle="Raised from the live pipeline state: connections, lag, failures and rejections." live>
        {!data ? (
          <Empty>Waiting for the service.</Empty>
        ) : alerts.length === 0 ? (
          <Empty>No alerts. Everything is running normally.</Empty>
        ) : (
          <ul className="timeline">
            {alerts.map((a, i) => (
              <li key={i}>
                <span className={`dot ${a.tone === "bad" ? "bad" : a.tone === "warn" ? "" : "muted"}`} />
                <div>
                  <div className="what">{a.title}</div>
                  <div className="detail">{a.text}</div>
                </div>
                <Link href={a.href} className="link" style={{ fontSize: 12.5 }}>
                  Open
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card
        icon={Siren}
        title="Dispatch and escalations"
        subtitle="KAFKA_SINK: ops.notifications"
        monoSubtitle
        live={false}
        actions={
          <button type="button" className="icon-button" aria-label="Refresh">
            <RefreshCw size={17} />
          </button>
        }
      >
        <ul className="timeline">
          <li>
            <span className="dot bad" />
            <div>
              <div className="what">Ops desk webhook P1</div>
              <div className="detail">Over-speed sustained &gt; 60s (vehicle #108)</div>
              <div className="meta">HTTP 200 · Delivered in 42ms</div>
            </div>
            <span className="when">Just now</span>
          </li>
          <li>
            <span className="dot" />
            <div>
              <div className="what">Driver in-cab push notification</div>
              <div className="detail">Corridor departure warning sounded</div>
              <div className="meta">FCM ACK · Device online</div>
            </div>
            <span className="when">2m ago</span>
          </li>
          <li>
            <span className="dot muted" />
            <div>
              <div className="what">Incident ledger auto-log</div>
              <div className="detail">Audit ticket created in PostgreSQL sink</div>
              <div className="meta">UUID: #d89f-43b2-981c</div>
            </div>
            <span className="when">8m ago</span>
          </li>
        </ul>
        <div className="card-actions">
          <DemoButton primary icon={Webhook}>
            Broadcast test incident webhook
          </DemoButton>
        </div>
      </Card>

      <Card icon={Webhook} title="Escalation channels" subtitle="Where each severity is sent." live={false}>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Channel</th>
                <th>Target</th>
                <th>Delivery</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>
                  <Tag tone="bad">P1 CRITICAL</Tag>
                </td>
                <td>Webhook</td>
                <td className="mono muted">ops-desk.internal/hooks/p1</td>
                <td className="good">Healthy</td>
              </tr>
              <tr>
                <td>
                  <Tag tone="warn">P2 WARNING</Tag>
                </td>
                <td>In-cab push</td>
                <td className="mono muted">FCM topic: drivers</td>
                <td className="good">Healthy</td>
              </tr>
              <tr>
                <td>
                  <Tag tone="neutral">P3 INFO</Tag>
                </td>
                <td>Incident ledger</td>
                <td className="mono muted">PostgreSQL: incident_log</td>
                <td className="good">Healthy</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
