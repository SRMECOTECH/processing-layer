"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutGrid, Waves } from "@/components/icons";
import { SlideMarker } from "@/components/slide-marker";
import { SECTIONS, sectionFor } from "@/lib/nav";
import { useNotifications } from "@/lib/notifications";

/**
 * The black capsule on the left: one icon per section, with the open alert and unread counts. The lavender
 * circle and the notch on its right edge slide to the current section; hovering an icon shows its name.
 * On load the capsule grows down from the top.
 */
export function Rail() {
  const pathname = usePathname();
  const active = sectionFor(pathname).href;
  const { openAlerts, unread } = useNotifications();
  const count: Record<string, number> = { "/alerts": openAlerts, "/notifications": unread };

  return (
    <aside className="rail" aria-label="Sections">
      <Link href="/live" className="rail-logo rail-appear" style={{ "--n": 0 } as React.CSSProperties} aria-label="PulseStream home">
        <Waves size={22} strokeWidth={2.2} />
      </Link>

      <nav className="rail-nav">
        {SECTIONS.map((section, n) => {
          const current = section.href === active;
          const Icon = section.icon;
          const badge = count[section.href] ?? 0;
          return (
            <Link
              key={section.href}
              href={section.href}
              className="rail-item rail-appear"
              style={{ "--n": n + 1 } as React.CSSProperties}
              aria-current={current ? "page" : undefined}
              aria-label={badge > 0 ? `${section.label} (${badge})` : section.label}
            >
              {current && (
                <>
                  <SlideMarker id="rail-circle" className="rail-circle" />
                  <SlideMarker id="rail-notch" className="rail-notch" />
                </>
              )}
              <Icon size={18} strokeWidth={1.75} className="rail-icon" />
              {badge > 0 && <span className="rail-count">{badge > 99 ? "99+" : badge}</span>}
              <span className="rail-tip" role="tooltip">
                {section.label}
              </span>
            </Link>
          );
        })}
      </nav>

      <a href="/studio" className="rail-item rail-bottom rail-appear" style={{ "--n": SECTIONS.length + 1 } as React.CSSProperties} aria-label="Workspace Studio">
        <LayoutGrid size={18} strokeWidth={1.75} className="rail-icon" />
        <span className="rail-tip" role="tooltip">
          Workspace Studio
        </span>
      </a>
    </aside>
  );
}
