"use client";

import { useState } from "react";
import type { LucideIcon } from "lucide-react";
import { Bell, Mail, MessageSquare, Smartphone, Webhook, Hash } from "lucide-react";
import { Callout, DemoButton, PageHeader, Switch, Tag } from "@/components/ops/kit";

type Channel = { id: string; name: string; icon: LucideIcon; target: string; note: string; built: boolean };

const CHANNELS: Channel[] = [
  { id: "inapp", name: "In-app", icon: Bell, target: "This console: bell and inbox", note: "Platform problems and high/critical vehicle alerts", built: true },
  { id: "email", name: "Email", icon: Mail, target: "ops-desk@example.com", note: "Digest or per alert", built: false },
  { id: "sms", name: "SMS", icon: MessageSquare, target: "+91 ••••• ••210", note: "Critical only, to the duty manager", built: false },
  { id: "push", name: "Driver app push", icon: Smartphone, target: "FCM topic: drivers", note: "In-cab warning for over-speed", built: false },
  { id: "webhook", name: "Webhook", icon: Webhook, target: "https://ops.example.com/hooks/gps", note: "JSON body, signed, retried 3 times", built: false },
  { id: "chat", name: "Slack / Teams", icon: Hash, target: "#fleet-alerts", note: "Channel message with a link back", built: false },
];

export default function ChannelsPage() {
  const [enabled, setEnabled] = useState<Record<string, boolean>>({ inapp: true, email: true, sms: false, push: true, webhook: false, chat: false });

  return (
    <div className="stack">
      <PageHeader
        title="Channels"
        description="Where notifications can be delivered. Only the in-app channel exists today; the others show how each will be set up."
        actions={<DemoButton primary>Add channel</DemoButton>}
      />

      <Callout tone="planned" title="Planned">
        Delivery outside this console needs a notification service (a consumer of gps.vehicle-events). Switches here are kept on this page only.
      </Callout>

      <div className="channel-grid">
        {CHANNELS.map((c) => {
          const Icon = c.icon;
          return (
            <div key={c.id} className={`channel${enabled[c.id] ? " on" : ""}`}>
              <div className="channel-head">
                <span className="card-icon">
                  <Icon size={17} />
                </span>
                <div className="channel-name">
                  {c.name}
                  {c.built ? <Tag tone="good">LIVE</Tag> : <Tag tone="neutral">PLANNED</Tag>}
                </div>
                <Switch checked={!!enabled[c.id]} onChange={(on) => setEnabled((e) => ({ ...e, [c.id]: on }))} label={`${c.name} channel`} disabled={c.built} />
              </div>
              <div className="channel-target mono">{c.target}</div>
              <p className="channel-note">{c.note}</p>
              <div className="channel-foot">{!c.built && <DemoButton>Send test</DemoButton>}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
