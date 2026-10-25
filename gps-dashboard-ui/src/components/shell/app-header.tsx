"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { Bell, CircleUser, PanelLeft, Search, SlidersHorizontal } from "lucide-react";
import { alertsFrom, seriousCount } from "@/lib/alerts";
import { formatRate } from "@/lib/format";
import { allPages } from "@/lib/nav";
import { useOverview } from "@/lib/overview-context";
import { ingress, isKafkaFlow } from "@/lib/pipeline";
import { usePreferences, type Accent, type Background } from "@/lib/preferences";
import { Popover } from "./popover";

/** The bar above the page, on the lilac canvas: menu toggle, product name, search, live rate, alerts, appearance. */
export function AppHeader() {
  const { toggleSidebar } = usePreferences();
  const { data: overview, error } = useOverview();
  const kafka = isKafkaFlow(overview);
  const rate = ingress(overview)?.messagesPerSecond;

  return (
    <header className="topbar">
      <div className="topbar-left">
        <button type="button" className="icon-button" aria-label="Show or hide the menu" onClick={toggleSidebar}>
          <PanelLeft size={17} strokeWidth={1.8} />
        </button>
        <Link href="/stream" className="logo">
          <span className="logo-name">PulseStream IoT</span>
          <span className="tag">{overview ? (kafka ? "PROD KAFKA" : "DIRECT FLOW") : "CONNECTING"}</span>
        </Link>
      </div>

      <SearchBox />

      <div className="topbar-right">
        <span className={`live-pill${error && !overview ? " down" : ""}`} title="Frames entering the pipeline, average of the last 10 seconds">
          <i />
          {error && !overview ? "Service offline" : `${kafka ? "Kafka" : "Direct"}: ${formatRate(rate)} msg/s`}
        </span>
        <Notifications />
        <ThemeSettings />
        <span className="avatar" aria-label="Signed in user">
          <CircleUser size={20} strokeWidth={1.7} />
        </span>
      </div>
    </header>
  );
}

function SearchBox() {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [focused, setFocused] = useState(false);
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        inputRef.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    const pages = allPages()
      .filter((p) => `${p.label} ${p.section}`.toLowerCase().includes(q))
      .slice(0, 7)
      .map((p) => ({ href: p.href, title: p.label, hint: p.section }));
    if (/^\d{3,}$/.test(q)) {
      pages.unshift({ href: `/stream/vehicle-state?imei=${q}`, title: `Vehicles with IMEI containing ${q}`, hint: "Vehicle State" });
      pages.unshift({ href: `/stream/tracking?imei=${q}`, title: `Track vehicle ${q}`, hint: "Vehicle Tracking" });
    }
    return pages;
  }, [query]);

  function go(href: string) {
    router.push(href);
    setQuery("");
    inputRef.current?.blur();
  }

  return (
    <div className="search">
      <Search size={15} className="search-icon" />
      <input
        ref={inputRef}
        className="search-input"
        placeholder="Filter processors, pages, partitions or an IMEI"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setActive(0);
        }}
        onFocus={() => setFocused(true)}
        onBlur={() => setTimeout(() => setFocused(false), 120)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown") {
            event.preventDefault();
            setActive((i) => Math.min(i + 1, results.length - 1));
          } else if (event.key === "ArrowUp") {
            event.preventDefault();
            setActive((i) => Math.max(i - 1, 0));
          } else if (event.key === "Enter" && results[active]) {
            go(results[active].href);
          } else if (event.key === "Escape") {
            setQuery("");
          }
        }}
        aria-label="Search"
      />
      <kbd className="search-kbd">Ctrl K</kbd>
      {focused && query.trim() && (
        <div className="search-results" role="listbox">
          {results.length === 0 ? (
            <div className="search-empty">Nothing matches “{query.trim()}”.</div>
          ) : (
            results.map((r, i) => (
              <button
                type="button"
                key={r.href}
                className={`search-result${i === active ? " active" : ""}`}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => go(r.href)}
                onMouseEnter={() => setActive(i)}
              >
                <span>{r.title}</span>
                <span className="search-hint">{r.hint}</span>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
}

function Notifications() {
  const { data: overview } = useOverview();
  const alerts = alertsFrom(overview);
  const serious = seriousCount(alerts);

  return (
    <Popover
      label="Alerts"
      width={340}
      buttonClassName="icon-button ink"
      button={
        <>
          <Bell size={16} strokeWidth={1.8} />
          {serious > 0 && <span className="bell-dot" />}
        </>
      }
    >
      {(close) => (
        <div>
          <div className="popover-title">Alerts</div>
          {alerts.length === 0 ? (
            <p className="popover-empty">Everything is running normally.</p>
          ) : (
            <ul className="alert-list">
              {alerts.map((a, i) => (
                <li key={i}>
                  <Link href={a.href} onClick={close} className="alert-item">
                    <span className={`alert-dot ${a.tone}`} />
                    <span>
                      <span className="alert-title">{a.title}</span>
                      <span className="alert-text">{a.text}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Popover>
  );
}

const BACKGROUNDS: [Background, string, string][] = [
  ["lilac", "Lilac", "#ece9ef"],
  ["mist", "Mist", "#e9edf2"],
  ["warm", "Warm", "#efebe6"],
];
const ACCENTS: [Accent, string, string][] = [
  ["lavender", "Lavender", "#5a4ea6"],
  ["sky", "Sky blue", "#2f63c8"],
];

function ThemeSettings() {
  const { background, accent, setBackground, setAccent } = usePreferences();
  return (
    <Popover label="Appearance" width={280} button={<SlidersHorizontal size={16} strokeWidth={1.8} />}>
      {() => (
        <div>
          <div className="popover-title">Appearance</div>
          <div className="setting-label">Background</div>
          <div className="swatches">
            {BACKGROUNDS.map(([value, label, color]) => (
              <button
                type="button"
                key={value}
                className={`swatch${background === value ? " selected" : ""}`}
                onClick={() => setBackground(value)}
              >
                <span className="swatch-color" style={{ background: color }} />
                {label}
              </button>
            ))}
          </div>
          <div className="setting-label">Accent</div>
          <div className="swatches">
            {ACCENTS.map(([value, label, color]) => (
              <button
                type="button"
                key={value}
                className={`swatch${accent === value ? " selected" : ""}`}
                onClick={() => setAccent(value)}
              >
                <span className="swatch-color" style={{ background: color }} />
                {label}
              </button>
            ))}
          </div>
        </div>
      )}
    </Popover>
  );
}
