"use client";

import { usePathname } from "next/navigation";
import { useLayoutEffect, useRef } from "react";

// What blurs in on a new page, one after another in reading (document) order.
// Keep in step with the selector in globals.css ("motion").
const BLUR_IN = [
  ".crumbs, .hdr-desc, .hdr-actions, .hdr-meta, .card, .callout, .section, .page-lead, .page-meta, .page-head + .stats > *",
  ".tile:not(.card .tile, .section .tile, .inner .tile, .modal .tile)",
].join(", ");
const MAX_STEPS = 14;

/**
 * Keyed by the path, so every page change (also within a section) starts the entry motion again.
 * Each block gets its place in the stagger as `--i`; the animation itself is CSS (globals.css, "motion").
 */
export default function Template({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  return <Page key={pathname}>{children}</Page>;
}

function Page({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    ref.current?.querySelectorAll<HTMLElement>(BLUR_IN).forEach((el, n) => {
      el.style.setProperty("--i", String(Math.min(n, MAX_STEPS)));
    });
  }, []);
  return (
    <div className="page" ref={ref}>
      {children}
    </div>
  );
}
