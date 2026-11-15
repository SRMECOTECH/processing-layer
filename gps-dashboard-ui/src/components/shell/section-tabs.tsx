"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef } from "react";
import { SlideMarker } from "@/components/slide-marker";
import { sectionFor } from "@/lib/nav";
import { useSections } from "@/lib/viewer";
import { useNotifications } from "@/lib/notifications";

/**
 * The sections, in the top bar: a black capsule with one tab per section and the open alert and unread counts; the
 * lavender pill slides to the current one. Only a click here changes the section. The pages of a section are in the
 * menu on the left, and nothing in that menu leads to another section, so the two never show the same options.
 */
export function SectionTabs() {
  const pathname = usePathname();
  const sections = useSections();
  const active = sectionFor(pathname, sections).href;
  const { openAlerts, unread } = useNotifications();
  const count: Record<string, number> = {
    "/alerts": openAlerts,
    "/notifications": unread,
    "/transporter/alerts": openAlerts,
    "/consigner/alerts": openAlerts,
  };
  const current = useRef<HTMLAnchorElement>(null);

  // On a narrow screen the capsule scrolls sideways: keep the current section in view.
  useEffect(() => {
    current.current?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [active]);

  return (
    <nav className="sections" aria-label="Sections">
      {sections.map((section) => {
        const isCurrent = section.href === active;
        const Icon = section.icon;
        const badge = count[section.href] ?? 0;
        return (
          <Link
            key={section.href}
            ref={isCurrent ? current : undefined}
            href={section.href}
            className="section-tab"
            aria-current={isCurrent ? "page" : undefined}
            title={section.description}
          >
            {isCurrent && <SlideMarker id="section-pill" className="section-pill" />}
            <Icon size={15} strokeWidth={1.9} className="section-icon" />
            <span className="section-label">{section.label}</span>
            {badge > 0 && <span className="section-count">{badge > 99 ? "99+" : badge}</span>}
          </Link>
        );
      })}
    </nav>
  );
}
