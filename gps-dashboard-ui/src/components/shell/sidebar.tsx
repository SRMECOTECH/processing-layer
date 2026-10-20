"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { sectionFor } from "@/lib/nav";

/** The left menu of the current section, grouped like the design: small caps headings, icon + label rows. */
export function Sidebar() {
  const pathname = usePathname();
  const section = sectionFor(pathname);

  return (
    <aside className="sidebar" aria-label={`${section.label} pages`}>
      {section.groups.map((group) => (
        <div className="side-group" key={group.title}>
          <div className="side-title">{group.title}</div>
          {group.items.map((item) => {
            const Icon = item.icon;
            const current = pathname === item.href;
            return (
              <Link key={item.href} href={item.href} className="side-link" aria-current={current ? "page" : undefined}>
                <Icon size={16} strokeWidth={current ? 2.2 : 1.8} />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </div>
      ))}
    </aside>
  );
}
