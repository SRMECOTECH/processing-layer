"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

export type Theme = "light" | "dark" | "system";
export type Accent = "lavender" | "navy" | "sky" | "indigo" | "teal" | "violet";
export type Background = "lilac" | "mist" | "warm";
export type Density = "comfortable" | "compact";

type Saved = {
  theme: Theme;
  accent: Accent;
  background: Background;
  density: Density;
  sidebarOpen: boolean;
  paused: boolean;
};

type Preferences = Saved & {
  setTheme: (value: Theme) => void;
  setAccent: (value: Accent) => void;
  setBackground: (value: Background) => void;
  setDensity: (value: Density) => void;
  setPaused: (value: boolean) => void;
  toggleSidebar: () => void;
  /** The phone-width menu drawer; never saved. */
  drawerOpen: boolean;
  setDrawerOpen: (value: boolean) => void;
};

const DEFAULTS: Saved = { theme: "light", accent: "lavender", background: "lilac", density: "comfortable", sidebarOpen: true, paused: false };
const BACKGROUNDS: Background[] = ["lilac", "mist", "warm"];
const STORAGE_KEY = "pulsestream.preferences";
const PreferencesContext = createContext<Preferences | null>(null);

/**
 * Runs before React starts (see layout.tsx) so the saved look is applied without a flash.
 * Kept as a string because it is inlined into the page head.
 */
export const APPLY_SAVED_THEME = `try{var p=JSON.parse(localStorage.getItem("${STORAGE_KEY}")||"{}");var d=document.documentElement;var t=p.theme||"light";if(t==="system")t=matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light";d.dataset.theme=t;if(p.background)d.dataset.bg=p.background;if(p.accent)d.dataset.accent=p.accent;if(p.density)d.dataset.density=p.density;if(p.sidebarOpen===false)d.dataset.sidebar="closed";}catch(e){}`;

export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [saved, setSaved] = useState<Saved>(DEFAULTS);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    try {
      const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "{}") as Partial<Saved>;
      // Backgrounds saved by the earlier look (off-white, white) no longer exist: fall back to the default.
      if (stored.background && !BACKGROUNDS.includes(stored.background)) delete stored.background;
      setSaved((current) => ({ ...current, ...stored }));
    } catch {
      // Storage blocked or unreadable: keep the defaults.
    }
    setLoaded(true);
  }, []);

  useEffect(() => {
    // The head script already applied the saved look; wait for it to be read so the defaults never flash.
    if (!loaded) return;
    const root = document.documentElement;
    const dark = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      root.dataset.theme = saved.theme === "system" ? (dark.matches ? "dark" : "light") : saved.theme;
    };
    apply();
    root.dataset.bg = saved.background;
    root.dataset.accent = saved.accent;
    root.dataset.density = saved.density;
    root.dataset.sidebar = saved.sidebarOpen ? "open" : "closed";
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(saved));
    } catch {
      // Not saved; the choice still applies for this visit.
    }
    if (saved.theme !== "system") return;
    dark.addEventListener("change", apply);
    return () => dark.removeEventListener("change", apply);
  }, [saved, loaded]);

  const update = useCallback((patch: Partial<Saved>) => setSaved((current) => ({ ...current, ...patch })), []);
  const toggleSidebar = useCallback(() => setSaved((current) => ({ ...current, sidebarOpen: !current.sidebarOpen })), []);

  return (
    <PreferencesContext.Provider
      value={{
        ...saved,
        setTheme: (theme) => update({ theme }),
        setAccent: (accent) => update({ accent }),
        setBackground: (background) => update({ background }),
        setDensity: (density) => update({ density }),
        setPaused: (paused) => update({ paused }),
        toggleSidebar,
        drawerOpen,
        setDrawerOpen,
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

/** True while the viewer has paused live updates; polling keeps the last numbers on screen. */
export function useLiveUpdatesPaused(): boolean {
  return useContext(PreferencesContext)?.paused ?? false;
}
