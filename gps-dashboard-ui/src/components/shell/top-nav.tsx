"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useLayoutEffect, useRef, useState } from "react";
import { SECTIONS, sectionFor } from "@/lib/nav";
import { useNotifications } from "@/lib/notifications";

/** The fixed sections across the top bar. A thin accent line slides under the current one. */
export function TopNav() {
  const pathname = usePathname();
  const active = sectionFor(pathname).href;
  const { openAlerts, unread } = useNotifications();
  const ref = useRef<HTMLElement>(null);
  const [bar, setBar] = useState<{ left: number; width: number } | null>(null);

  useLayoutEffect(() => {
    const nav = ref.current;
    if (!nav) return;
    const measure = () => {
      const link = nav.querySelector<HTMLAnchorElement>('[aria-current="page"]');
      setBar(link ? { left: link.offsetLeft + 12, width: link.offsetWidth - 24 } : null);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(nav);
    return () => observer.disconnect();
  }, [active, openAlerts, unread]);

  const count: Record<string, number> = { "/alerts": openAlerts, "/notifications": unread };

  return (
    <nav className="topnav" ref={ref} aria-label="Sections">
      {SECTIONS.map((section) => {
        const Icon = section.icon;
        const n = count[section.href] ?? 0;
        return (
          <Link
            key={section.href}
            href={section.href}
            className="topnav-link"
            aria-current={section.href === active ? "page" : undefined}
            title={section.description}
          >
            <Icon size={16} className="topnav-icon" />
            <span>{section.label}</span>
            {n > 0 && <span className={`count${section.href === "/alerts" ? " bad" : ""}`}>{n > 99 ? "99+" : n}</span>}
          </Link>
        );
      })}
      <span
        className="topnav-bar"
        aria-hidden="true"
        style={{ width: bar?.width ?? 0, transform: `translateX(${bar?.left ?? 0}px)`, opacity: bar ? 1 : 0 }}
      />
    </nav>
  );
}
