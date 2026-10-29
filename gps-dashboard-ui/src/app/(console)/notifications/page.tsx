"use client";

import Link from "next/link";
import { useState } from "react";
import { CheckCheck, Inbox } from "@/components/icons";
import { Card, Empty, PageHeader, Segmented, Stats, Tag, Tile } from "@/components/ops/kit";
import { formatAgo } from "@/lib/format";
import { useNotifications } from "@/lib/notifications";

type Filter = "all" | "unread" | "platform" | "vehicle";

export default function NotificationInboxPage() {
  const { notices, unread, isRead, markRead, markAllRead } = useNotifications();
  const [filter, setFilter] = useState<Filter>("all");
  const shown = notices.filter((n) => (filter === "all" ? true : filter === "unread" ? !isRead(n.id) : n.kind === filter));
  const platform = notices.filter((n) => n.kind === "platform").length;

  return (
    <div className="stack">
      <PageHeader
        title="All notifications"
        description="What you would be told about: platform problems and high or critical vehicle alerts. Read state is kept in this browser."
        actions={
          <button type="button" className="btn soft" onClick={markAllRead} disabled={unread === 0}>
            <CheckCheck size={15} />
            Mark all read
          </button>
        }
      />

      <Stats columns={3}>
        <Tile label="Unread" value={unread} tone={unread ? "accent" : undefined} big />
        <Tile label="Platform" value={platform} big foot="Kafka, Redis, PostgreSQL, lag, rejections" />
        <Tile label="Vehicle alerts" value={notices.length - platform} big foot="high and critical events" />
      </Stats>

      <Card
        icon={Inbox}
        title="Inbox"
        subtitle={`${shown.length} notifications`}
        live
        flush
        actions={
          <Segmented<Filter>
            label="Show"
            value={filter}
            onChange={setFilter}
            options={[
              ["all", "All"],
              ["unread", "Unread"],
              ["platform", "Platform"],
              ["vehicle", "Vehicles"],
            ]}
          />
        }
      >
        {shown.length === 0 ? (
          <Empty>{filter === "unread" ? "You are all caught up." : "Nothing here yet."}</Empty>
        ) : (
          <ul className="inbox">
            {shown.map((n) => (
              <li key={n.id} className={`inbox-row${isRead(n.id) ? "" : " unread"}`}>
                <span className={`status-dot ${n.tone === "info" ? "muted" : n.tone}`} />
                <div className="inbox-main">
                  <div className="inbox-title">{n.title}</div>
                  <div className="inbox-text">{n.text}</div>
                </div>
                <Tag tone="neutral">{n.kind === "platform" ? "PLATFORM" : "VEHICLE"}</Tag>
                <span className="inbox-time faint">{n.at ? formatAgo(n.at) : ""}</span>
                <span className="inbox-actions">
                  {!isRead(n.id) && (
                    <button type="button" className="text-button" onClick={() => markRead(n.id)}>
                      Mark read
                    </button>
                  )}
                  <Link href={n.href} className="text-button" onClick={() => markRead(n.id)}>
                    Open
                  </Link>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
