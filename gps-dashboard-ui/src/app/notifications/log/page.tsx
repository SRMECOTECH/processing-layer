"use client";

import { useState } from "react";
import { History } from "lucide-react";
import { Card, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";

type Status = "DELIVERED" | "FAILED" | "RETRYING";
const LOG: { time: string; channel: string; to: string; alert: string; status: Status; ms: number }[] = [
  { time: "14:22:07", channel: "Push", to: "JH05BS0128 driver", alert: "Over-speed, GPS verified", status: "DELIVERED", ms: 412 },
  { time: "14:22:07", channel: "Email", to: "ops-desk@example.com", alert: "Over-speed, GPS verified", status: "DELIVERED", ms: 1280 },
  { time: "14:19:44", channel: "Webhook", to: "ops.example.com/hooks/gps", alert: "Unexplained stoppage", status: "RETRYING", ms: 3000 },
  { time: "14:11:02", channel: "SMS", to: "+91 ••••• ••210", alert: "Route deviation", status: "DELIVERED", ms: 2210 },
  { time: "13:58:31", channel: "Webhook", to: "ops.example.com/hooks/gps", alert: "Route deviation", status: "FAILED", ms: 3000 },
  { time: "13:47:15", channel: "Email", to: "ops-desk@example.com", alert: "Platform problem", status: "DELIVERED", ms: 960 },
];

export default function DeliveryLogPage() {
  const [filter, setFilter] = useState<"ALL" | Status>("ALL");
  const rows = LOG.filter((r) => filter === "ALL" || r.status === filter);
  const count = (s: Status) => LOG.filter((r) => r.status === s).length;

  return (
    <div className="stack">
      <PageHeader title="Delivery log" description="Every notification sent, to whom, through which channel, and whether it arrived." badges={<Tag tone="neutral">PLANNED</Tag>} />

      <Stats columns={3}>
        <Tile label="Delivered (example)" value={count("DELIVERED")} tone="good" big />
        <Tile label="Retrying (example)" value={count("RETRYING")} tone="warn" big />
        <Tile label="Failed (example)" value={count("FAILED")} tone="bad" big />
      </Stats>

      <Card
        icon={History}
        title="Deliveries"
        subtitle="Newest first."
        live={false}
        flush
        actions={
          <Segmented<"ALL" | Status>
            label="Status"
            value={filter}
            onChange={setFilter}
            options={[
              ["ALL", "All"],
              ["DELIVERED", "Delivered"],
              ["RETRYING", "Retrying"],
              ["FAILED", "Failed"],
            ]}
          />
        }
      >
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Sent</th>
                <th>Channel</th>
                <th>To</th>
                <th>Alert</th>
                <th className="right">Took</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={`${r.time}-${r.channel}`} className={r.status === "FAILED" ? "flagged" : undefined}>
                  <td className="mono">{r.time}</td>
                  <td>{r.channel}</td>
                  <td className="mono muted">{r.to}</td>
                  <td>{r.alert}</td>
                  <td className="right mono">{r.ms} ms</td>
                  <td>
                    <Tag tone={r.status === "DELIVERED" ? "good" : r.status === "FAILED" ? "bad" : "warn"}>{r.status}</Tag>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
