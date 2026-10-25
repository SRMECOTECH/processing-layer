"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

export type Background = "lilac" | "mist" | "warm";
export type Accent = "lavender" | "sky";

const BACKGROUNDS: Background[] = ["lilac", "mist", "warm"];
const ACCENTS: Accent[] = ["lavender", "sky"];

type Preferences = {
  background: Background;
  accent: Accent;
  sidebarOpen: boolean;
  setBackground: (value: Background) => void;
  setAccent: (value: Accent) => void;
  toggleSidebar: () => void;
};

const STORAGE_KEY = "pulsestream.preferences";
const PreferencesContext = createContext<Preferences | null>(null);

/**
 * Runs before React starts (see layout.tsx) so the saved colours are applied without a flash.
 * Kept as a string because it is inlined into the page head.
 */
export const APPLY_SAVED_THEME = `try{var p=JSON.parse(localStorage.getItem("${STORAGE_KEY}")||"{}");var d=document.documentElement;if(p.background)d.dataset.bg=p.background;if(p.accent)d.dataset.accent=p.accent;}catch(e){}`;

export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [background, setBackgroundState] = useState<Background>("lilac");
  const [accent, setAccentState] = useState<Accent>("lavender");
  const [sidebarOpen, setSidebarOpen] = useState(true);

  useEffect(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "{}") as Partial<Preferences>;
      // Values saved by the earlier design (off-white, navy...) no longer exist and fall back to the defaults.
      if (saved.background && BACKGROUNDS.includes(saved.background)) setBackgroundState(saved.background);
      if (saved.accent && ACCENTS.includes(saved.accent)) setAccentState(saved.accent);
      if (saved.sidebarOpen === false) setSidebarOpen(false);
    } catch {
      // Storage blocked or unreadable: keep the defaults.
    }
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.bg = background;
    root.dataset.accent = accent;
    root.dataset.sidebar = sidebarOpen ? "open" : "closed";
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ background, accent, sidebarOpen }));
    } catch {
      // Not saved; the choice still applies for this visit.
    }
  }, [background, accent, sidebarOpen]);

  const toggleSidebar = useCallback(() => setSidebarOpen((open) => !open), []);

  return (
    <PreferencesContext.Provider
      value={{
        background,
        accent,
        sidebarOpen,
        setBackground: setBackgroundState,
        setAccent: setAccentState,
        toggleSidebar,
      }}
    >
      {children}
    </PreferencesContext.Provider>
  );
}

export function usePreferences(): Preferences {
  const value = useContext(PreferencesContext);
  if (!value) throw new Error("usePreferences must be used inside PreferencesProvider");
  return value;
}
