"use client";

import { useLayoutEffect, useRef } from "react";

// Where each marker (by id) was when it was last removed, and when.
const last = new Map<string, { rect: DOMRect; at: number }>();

const EASE = "cubic-bezier(0.22, 1, 0.36, 1)";

/**
 * A highlight that slides from the previous active item to the new one (the black pill behind the open page,
 * the lavender circle on the rail). Only the active item renders it: when the active item changes, the old
 * marker is removed and the new one mounts in the same render, so the new one starts where the old one was
 * and glides into place with the Web Animations API. No animation library.
 * Position it with top/left/inset, not `transform`, which the slide uses.
 */
export function SlideMarker({ id, className, duration = 420 }: { id: string; className?: string; duration?: number }) {
  const ref = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const from = last.get(id);
    // Only a marker removed in this same render counts; one left behind on another page does not.
    if (from && performance.now() - from.at < 100 && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      const to = el.getBoundingClientRect();
      const dx = from.rect.left - to.left;
      const dy = from.rect.top - to.top;
      const sx = to.width ? from.rect.width / to.width : 1;
      const sy = to.height ? from.rect.height / to.height : 1;
      if (dx || dy || sx !== 1 || sy !== 1) {
        el.animate(
          [
            { transformOrigin: "0 0", transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
            { transformOrigin: "0 0", transform: "none" },
          ],
          { duration, easing: EASE },
        );
      }
    }
    // Measured while it is still on screen (mid-slide, if it is moving), just before React removes it.
    return () => {
      last.set(id, { rect: el.getBoundingClientRect(), at: performance.now() });
    };
  }, [id, duration]);

  return <span ref={ref} className={className} aria-hidden="true" />;
}
