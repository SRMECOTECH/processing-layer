"use client";

import { useEffect, useState } from "react";
import { PREFERENCES_KEY, type StudioTheme } from "./theme";

/**
 * The theme the head script applied (see theme.ts), and a switch that flips it and saves the choice in the
 * preferences the console reads too. The page cross-fades where the browser supports View Transitions.
 */
export function useTheme(): [StudioTheme, () => void] {
  const [theme, setTheme] = useState<StudioTheme>("light");

  useEffect(() => {
    setTheme(document.documentElement.dataset.theme === "dark" ? "dark" : "light");
  }, []);

  function toggle() {
    const next: StudioTheme = theme === "dark" ? "light" : "dark";
    const apply = () => {
      document.documentElement.dataset.theme = next;
      setTheme(next);
    };
    const doc = document as Document & { startViewTransition?: (update: () => void) => unknown };
    if (doc.startViewTransition && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) doc.startViewTransition(apply);
    else apply();
    try {
      const saved = JSON.parse(localStorage.getItem(PREFERENCES_KEY) ?? "{}");
      localStorage.setItem(PREFERENCES_KEY, JSON.stringify({ ...saved, theme: next }));
    } catch {
      // Storage blocked: the switch still applies for this visit.
    }
  }

  return [theme, toggle];
}
