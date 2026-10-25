"use client";

import { useEffect, useRef, useState } from "react";

type Props = {
  value: number | null | undefined;
  format: (value: number) => string;
  durationMillis?: number;
};

// A number seen for the first time counts up from this share of itself.
const ENTRY_FROM = 0.8;
const ENTRY_MILLIS = 800;

const entryValue = (value: number | null | undefined) =>
  value === null || value === undefined || Number.isNaN(value) ? null : value * ENTRY_FROM;

/** A number that counts up when it first appears, then glides from its previous value to each new one. */
export function AnimatedNumber({ value, format, durationMillis = 650 }: Props) {
  const [shown, setShown] = useState<number | null>(() => entryValue(value));
  const shownRef = useRef<number | null>(entryValue(value));

  useEffect(() => {
    if (value === null || value === undefined || Number.isNaN(value)) {
      shownRef.current = null;
      setShown(null);
      return;
    }
    const entering = shownRef.current === null;
    const from = entering ? value * ENTRY_FROM : shownRef.current!;
    const duration = entering ? ENTRY_MILLIS : durationMillis;
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (from === value || reduceMotion) {
      shownRef.current = value;
      setShown(value);
      return;
    }
    const start = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const progress = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      const current = from + (value - from) * eased;
      shownRef.current = current;
      setShown(current);
      if (progress < 1) {
        frame = requestAnimationFrame(step);
      }
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [value, durationMillis]);

  return <>{shown === null ? "–" : format(shown)}</>;
}
