"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { sectionFor } from "@/lib/nav";

/**
 * The white menu card next to the rail: the current section's sub-pages in groups. A black pill slides to the
 * open page; the items blur in again when the section changes.
 */
export function Sidebar() {
  const pathname = usePathname();
  const section = sectionFor(pathname);
  let n = 0;

  return (
    <aside className="sidebar" aria-label={`${section.label} pages`}>
      <div key={section.href} className="side-body">
        <div className="side-head side-appear" style={{ "--n": n++ } as React.CSSProperties}>
          <div className="side-section">{section.label}</div>
          <div className="side-caption">
            {section.groups.reduce((sum, g) => sum + g.items.length, 0)} pages
          </div>
        </div>
        {section.groups.map((group) => (
          <div className="side-group" key={group.title}>
            <div className="side-title side-appear" style={{ "--n": n++ } as React.CSSProperties}>
              {group.title}
            </div>
            {group.items.map((item) => {
              const Icon = item.icon;
              const current = pathname === item.href;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className="side-link side-appear"
                  style={{ "--n": n++ } as React.CSSProperties}
                  aria-current={current ? "page" : undefined}
                >
                  {current && (
                    <motion.span layoutId="side-pill" className="side-pill" transition={{ type: "spring", stiffness: 400, damping: 34 }} />
                  )}
                  <Icon size={16} strokeWidth={current ? 2 : 1.7} />
                  <span>{item.label}</span>
                </Link>
              );
            })}
          </div>
        ))}
      </div>
    </aside>
  );
}
