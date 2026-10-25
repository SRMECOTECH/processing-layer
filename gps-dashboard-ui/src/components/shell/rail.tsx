"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { LayoutGrid, Waves } from "lucide-react";
import { SECTIONS, sectionFor } from "@/lib/nav";

const SPRING = { type: "spring", stiffness: 400, damping: 32 } as const;

/**
 * The black capsule on the left: one icon per section. The lavender circle and the notch on its right edge
 * slide to the active section; hovering an icon shows its name. On load the capsule grows down from the top.
 */
export function Rail() {
  const pathname = usePathname();
  const active = sectionFor(pathname).href;

  return (
    <aside className="rail" aria-label="Sections">
      <Link href="/stream" className="rail-logo rail-appear" style={{ "--n": 0 } as React.CSSProperties} aria-label="PulseStream IoT">
        <Waves size={22} strokeWidth={2.2} />
      </Link>

      <nav className="rail-nav">
        {SECTIONS.map((section, n) => {
          const current = section.href === active;
          const Icon = section.icon;
          return (
            <Link
              key={section.href}
              href={section.href}
              className="rail-item rail-appear"
              style={{ "--n": n + 1 } as React.CSSProperties}
              aria-current={current ? "page" : undefined}
              aria-label={section.label}
            >
              {current && (
                <>
                  <motion.span layoutId="rail-circle" className="rail-circle" transition={SPRING} />
                  <motion.span layoutId="rail-notch" className="rail-notch" transition={SPRING} />
                </>
              )}
              <Icon size={18} strokeWidth={1.75} className="rail-icon" />
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
