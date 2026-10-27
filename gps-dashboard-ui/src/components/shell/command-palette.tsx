"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, Car, Crosshair, Search } from "@/components/icons";
import type { IconComponent } from "@/components/icons";
import { allPages } from "@/lib/nav";

type Result = { href: string; title: string; trail: string; icon: IconComponent; planned?: boolean };

/** Ctrl K (or /) anywhere: jump to any page, or straight to a vehicle by IMEI. */
export function SearchButton() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const typing = event.target instanceof HTMLElement && /^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName);
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setOpen((o) => !o);
      } else if (event.key === "/" && !typing) {
        event.preventDefault();
        setOpen(true);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <>
      <button type="button" className="search-button" onClick={() => setOpen(true)} aria-label="Search pages and vehicles">
        <Search size={16} />
        <span className="search-button-text">Search</span>
        <kbd>Ctrl K</kbd>
      </button>
      {open && <Palette onClose={() => setOpen(false)} />}
    </>
  );
}

function Palette({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const results = useMemo<Result[]>(() => {
    const q = query.trim().toLowerCase();
    const pages = allPages()
      .filter((p) => !q || `${p.label} ${p.parent ?? ""} ${p.section}`.toLowerCase().includes(q))
      .map((p) => ({
        href: p.href,
        title: p.label,
        trail: [p.section, p.parent].filter(Boolean).join(" › "),
        icon: p.icon,
        planned: p.feed === "planned",
      }));
    if (/^\d{3,}$/.test(q)) {
      pages.unshift(
        { href: `/live/inspector?imei=${q}`, title: `Inspect vehicle ${q}`, trail: "Live › Vehicle inspector", icon: Crosshair, planned: false },
        { href: `/live/vehicles?imei=${q}`, title: `Vehicles with IMEI containing ${q}`, trail: "Live › Vehicle list", icon: Car, planned: false },
      );
    }
    return pages.slice(0, 40);
  }, [query]);

  useEffect(() => {
    listRef.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: "nearest" });
  }, [active]);

  function go(href: string) {
    router.push(href);
    onClose();
  }

  return (
    <div className="palette-backdrop" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="palette" role="dialog" aria-modal="true" aria-label="Search">
        <div className="palette-input">
          <Search size={18} />
          <input
            ref={inputRef}
            value={query}
            placeholder="Go to a page, or type an IMEI"
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setActive((i) => Math.min(i + 1, results.length - 1));
              } else if (e.key === "ArrowUp") {
                e.preventDefault();
                setActive((i) => Math.max(i - 1, 0));
              } else if (e.key === "Enter" && results[active]) {
                go(results[active].href);
              } else if (e.key === "Escape") {
                onClose();
              }
            }}
            aria-label="Search"
          />
          <kbd>Esc</kbd>
        </div>
        <div className="palette-list" ref={listRef} role="listbox">
          {results.length === 0 ? (
            <p className="palette-empty">Nothing matches “{query.trim()}”.</p>
          ) : (
            results.map((r, i) => {
              const Icon = r.icon;
              return (
                <button
                  type="button"
                  key={`${r.href}-${r.title}`}
                  className="palette-item"
                  data-active={i === active}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => go(r.href)}
                  role="option"
                  aria-selected={i === active}
                >
                  <span className="palette-icon">
                    <Icon size={16} />
                  </span>
                  <span className="palette-text">
                    <span className="palette-title">{r.title}</span>
                    <span className="palette-trail">{r.trail}</span>
                  </span>
                  {r.planned && <span className="side-pill">Planned</span>}
                  <ArrowRight size={15} className="palette-go" />
                </button>
              );
            })
          )}
        </div>
        <div className="palette-foot">
          <span>
            <kbd>↑</kbd> <kbd>↓</kbd> to move
          </span>
          <span>
            <kbd>Enter</kbd> to open
          </span>
          <span>
            <kbd>/</kbd> opens search from anywhere
          </span>
        </div>
      </div>
    </div>
  );
}
