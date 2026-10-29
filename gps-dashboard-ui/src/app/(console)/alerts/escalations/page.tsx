"use client";

import Link from "next/link";
import { BellRing, Siren, Webhook } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Card, DemoButton, Empty, PageHeader, Tag } from "@/components/ops/kit";
import { alertsFrom } from "@/lib/alerts";
import { useOverview } from "@/lib/overview-context";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

export default function DispatchPage() {
  const { data } = useOverview();
  const alerts = alertsFrom(data);
  const events = usePoll<VehicleEventView[]>("/api/events?type=OVERSPEED,SPEED_SENSOR_MISMATCH,STOPPAGE,IDLING,HARSH_BRAKING,HARSH_ACCELERATION&limit=200", 4000);
  const escalations = events.data?.filter((e) => e.severity === "MEDIUM" || e.severity === "HIGH" || e.severity === "CRITICAL").slice(0, 25);

  return (
    <div className="stack">
      <PageHeader
        title="Escalations"
        description="Where alerts go once a rule fires: the operations desk, the driver's cab and the incident ledger. Channels themselves are set up under Notifications."
        actions={
          <Link href="/notifications/channels" className="btn soft">
            Channels
          </Link>
        }
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
        title="Escalation feed"
        subtitle="Medium-and-above events from the stream consumers, as published on Kafka topic gps.vehicle-events"
        live
        flush
      >
        <EventTable events={escalations} empty="Nothing to escalate yet." />
        <div className="card-actions">
          <DemoButton primary icon={Webhook}>
            Broadcast test incident webhook
          </DemoButton>
        </div>
      </Card>

      <Card icon={Webhook} title="Escalation matrix" subtitle="Where each severity is sent." live={false} flush>
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
