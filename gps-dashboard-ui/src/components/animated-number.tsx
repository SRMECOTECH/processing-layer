"use client";

import { useEffect, useRef, useState } from "react";

type Props = {
  value: number | null | undefined;
  format: (value: number) => string;
  durationMillis?: number;
};

/** A number that glides from its previous value to the new one instead of jumping. */
export function AnimatedNumber({ value, format, durationMillis = 650 }: Props) {
  const [shown, setShown] = useState<number | null>(value ?? null);
  const shownRef = useRef<number | null>(value ?? null);

  useEffect(() => {
    if (value === null || value === undefined || Number.isNaN(value)) {
      shownRef.current = null;
      setShown(null);
      return;
    }
    const from = shownRef.current;
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (from === null || from === value || reduceMotion) {
      shownRef.current = value;
      setShown(value);
      return;
    }
    const start = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const progress = Math.min(1, (now - start) / durationMillis);
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
