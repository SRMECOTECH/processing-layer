"use client";

import Link from "next/link";
import { Bell, CheckCheck, CircleUser, LogOut, Menu, SlidersHorizontal, Waves } from "@/components/icons";
import { signOut } from "@/lib/auth-actions";
import { formatAgo, formatRate } from "@/lib/format";
import { useNotifications } from "@/lib/notifications";
import { useOverview } from "@/lib/overview-context";
import { ingress, isKafkaFlow } from "@/lib/pipeline";
import { usePreferences, type Accent, type Background, type Density, type Theme } from "@/lib/preferences";
import { Switch } from "@/components/ops/kit";
import { SearchButton } from "./command-palette";
import { Popover } from "./popover";
import { SectionTabs } from "./section-tabs";

/**
 * The bar across the top of the frame: brand, the sections, then search, live rate, notifications, appearance and
 * user. It stays the same on every page of a section; the section's own pages are in the menu on the left.
 */
export function AppHeader({ user }: { user: string }) {
  const { setDrawerOpen } = usePreferences();

  return (
    <header className="topbar">
      <div className="topbar-start">
        <button type="button" className="icon-button menu-button" aria-label="Open this section's pages" onClick={() => setDrawerOpen(true)}>
          <Menu size={20} />
        </button>
        <Link href="/live" className="brand" aria-label="PulseStream home">
          <span className="brand-mark">
            <Waves size={17} />
          </span>
          <span className="brand-name">PulseStream</span>
        </Link>
      </div>

      <SectionTabs />

      <div className="topbar-end">
        <SearchButton />
        <LivePill />
        <NotificationBell />
        <AppearanceMenu />
        <UserMenu user={user} />
      </div>
    </header>
  );
}

function LivePill() {
  const { data: overview, error } = useOverview();
  const { paused, setPaused } = usePreferences();
  const kafka = isKafkaFlow(overview);
  const rate = ingress(overview)?.messagesPerSecond;
  const offline = !!error && !overview;
  const state = paused ? "paused" : offline ? "down" : overview ? "up" : "connecting";
  const text = paused
    ? "Updates paused"
    : offline
      ? "Service offline"
      : overview
        ? `${kafka ? "Kafka" : "Direct"} · ${formatRate(rate)} msg/s`
        : "Connecting";

  return (
    <button
      type="button"
      className={`live-pill ${state}`}
      onClick={() => setPaused(!paused)}
      title={paused ? "Live updates are paused. Click to resume." : "Frames entering the pipeline (10 s average). Click to pause live updates."}
    >
      <i />
      <span className="live-pill-text">{text}</span>
    </button>
  );
}

function NotificationBell() {
  const { notices, unread, isRead, markRead, markAllRead } = useNotifications();
  const latest = notices.slice(0, 6);

  return (
    <Popover
      label="Notifications"
      width={360}
      buttonClassName="icon-button ink"
      button={
        <>
          <Bell size={16} strokeWidth={1.8} />
          {unread > 0 && <span className="bell-count">{unread > 9 ? "9+" : unread}</span>}
        </>
      }
    >
      {(close) => (
        <div>
          <div className="popover-head">
            <span className="popover-title">Notifications</span>
            {unread > 0 && (
              <button type="button" className="text-button" onClick={markAllRead}>
                <CheckCheck size={14} />
                Mark all read
              </button>
            )}
          </div>
          {latest.length === 0 ? (
            <p className="popover-empty">You are all caught up.</p>
          ) : (
            <ul className="notice-list">
              {latest.map((n) => (
                <li key={n.id}>
                  <Link
                    href={n.href}
                    className={`notice-item${isRead(n.id) ? "" : " unread"}`}
                    onClick={() => {
                      markRead(n.id);
                      close();
                    }}
                  >
                    <span className={`status-dot ${n.tone === "info" ? "muted" : n.tone}`} />
                    <span className="notice-body">
                      <span className="notice-heading">{n.title}</span>
                      <span className="notice-sub">
                        {n.text}
                        {n.at && n.kind === "vehicle" ? ` · ${formatAgo(n.at)}` : ""}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          <Link href="/notifications" className="popover-footer-link" onClick={close}>
            Open notification center
          </Link>
        </div>
      )}
    </Popover>
  );
}

const THEMES: [Theme, string][] = [
  ["light", "Light"],
  ["dark", "Dark"],
  ["system", "System"],
];
const ACCENTS: [Accent, string, string][] = [
  ["lavender", "Lavender", "#5a4ea6"],
  ["navy", "Navy", "#0b4a8b"],
  ["sky", "Sky", "#0284c7"],
  ["indigo", "Indigo", "#4f46e5"],
  ["teal", "Teal", "#0f766e"],
  ["violet", "Violet", "#7c3aed"],
];
const BACKGROUNDS: [Background, string, string][] = [
  ["lilac", "Lilac", "#ece9ef"],
  ["mist", "Mist", "#e9edf2"],
  ["warm", "Warm", "#efebe6"],
];
const DENSITIES: [Density, string][] = [
  ["comfortable", "Comfortable"],
  ["compact", "Compact"],
];

function AppearanceMenu() {
  const p = usePreferences();
  return (
    <Popover label="Appearance and updates" width={330} button={<SlidersHorizontal size={16} strokeWidth={1.8} />}>
      {() => (
        <div className="settings">
          <div className="popover-title">Appearance</div>
          <div className="setting">
            <div className="setting-label">Theme</div>
            <Choice options={THEMES} value={p.theme} onChange={p.setTheme} />
          </div>
          <div className="setting">
            <div className="setting-label">Accent</div>
            <div className="accent-swatches">
              {ACCENTS.map(([value, label, color]) => (
                <button
                  type="button"
                  key={value}
                  className="accent-swatch"
                  aria-pressed={p.accent === value}
                  onClick={() => p.setAccent(value)}
                  title={label}
                  aria-label={`${label} accent`}
                  style={{ background: color }}
                />
              ))}
            </div>
          </div>
          <div className="setting">
            <div className="setting-label">Canvas (light theme)</div>
            <div className="bg-swatches">
              {BACKGROUNDS.map(([value, label, color]) => (
                <button type="button" key={value} className="bg-swatch" aria-pressed={p.background === value} onClick={() => p.setBackground(value)}>
                  <i style={{ background: color }} />
                  {label}
                </button>
              ))}
            </div>
          </div>
          <div className="setting">
            <div className="setting-label">Density</div>
            <Choice options={DENSITIES} value={p.density} onChange={p.setDensity} />
          </div>
          <div className="setting-divider" />
          <label className="switch-row">
            <span>
              <span className="switch-title">Live updates</span>
              <span className="switch-sub">{p.paused ? "Paused: numbers stay as they are" : "Polling the service every few seconds"}</span>
            </span>
            <Switch checked={!p.paused} onChange={(on) => p.setPaused(!on)} label="Live updates" />
          </label>
          <label className="switch-row">
            <span>
              <span className="switch-title">Expanded menu</span>
              <span className="switch-sub">Off shows icons only</span>
            </span>
            <Switch checked={p.sidebarOpen} onChange={p.toggleSidebar} label="Expanded menu" />
          </label>
        </div>
      )}
    </Popover>
  );
}

function UserMenu({ user }: { user: string }) {
  const { data: o } = useOverview();
  return (
    <Popover label="Account and environment" width={280} button={<CircleUser size={19} strokeWidth={1.7} />} buttonClassName="icon-button avatar-button">
      {() => (
        <div>
          <div className="user-card">
            <span className="user-avatar">{user.slice(0, 2).toUpperCase()}</span>
            <span>
              <span className="notice-heading">{user}</span>
              <span className="notice-sub">Signed in to this console</span>
            </span>
          </div>
          <dl className="kv compact">
            <div className="kv-row">
              <dt>Processing flow</dt>
              <dd>{o ? (isKafkaFlow(o) ? "Kafka" : "Direct") : "–"}</dd>
            </div>
            <div className="kv-row">
              <dt>Service</dt>
              <dd className="mono">/api → GPS_API_URL</dd>
            </div>
            <div className="kv-row">
              <dt>Last update</dt>
              <dd>{o ? formatAgo(o.generatedAt) : "–"}</dd>
            </div>
          </dl>
          <div className="shortcut-list">
            <span>
              <kbd>Ctrl K</kbd> Search
            </span>
            <span>
              <kbd>/</kbd> Search
            </span>
          </div>
          <form action={signOut} className="sign-out">
            <button type="submit" className="btn primary sign-out-button">
              <LogOut size={14} strokeWidth={1.9} />
              Sign out
            </button>
          </form>
        </div>
      )}
    </Popover>
  );
}

function Choice<T extends string>({ options, value, onChange }: { options: [T, string][]; value: T; onChange: (value: T) => void }) {
  return (
    <div className="segmented full" role="group">
      {options.map(([v, label]) => (
        <button key={v} type="button" aria-pressed={v === value} onClick={() => onChange(v)}>
          {label}
        </button>
      ))}
    </div>
  );
}
