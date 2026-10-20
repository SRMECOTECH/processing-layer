"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useLayoutEffect, useRef, useState } from "react";
import { SECTIONS, sectionFor } from "@/lib/nav";

/** The top tabs. A dark pill glides behind the active one. */
export function TopTabs() {
  const pathname = usePathname();
  const active = sectionFor(pathname).href;
  const ref = useRef<HTMLElement>(null);
  const [pill, setPill] = useState<{ left: number; width: number } | null>(null);

  useLayoutEffect(() => {
    const nav = ref.current;
    if (!nav) return;
    const measure = () => {
      const link = nav.querySelector<HTMLAnchorElement>('[aria-current="page"]');
      setPill(link ? { left: link.offsetLeft, width: link.offsetWidth } : null);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(nav);
    return () => observer.disconnect();
  }, [active]);

  return (
    <nav className="tabs" ref={ref} aria-label="Sections">
      <span
        className="tab-pill"
        aria-hidden="true"
        style={{ width: pill?.width ?? 0, transform: `translateX(${pill?.left ?? 0}px)`, opacity: pill ? 1 : 0 }}
      />
      {SECTIONS.map((section) => (
        <Link
          key={section.href}
          href={section.href}
          className="tab"
          aria-current={section.href === active ? "page" : undefined}
        >
          {section.label}
        </Link>
      ))}
    </nav>
  );
}
