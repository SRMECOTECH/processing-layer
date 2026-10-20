"use client";

import { createContext, useContext, type ReactNode } from "react";
import type { EngineOverview } from "./types";
import { usePoll, type Poll } from "./use-poll";

const OverviewContext = createContext<Poll<EngineOverview> | null>(null);

/** One shared poll of /api/engine/overview for the header and every page. */
export function OverviewProvider({ children }: { children: ReactNode }) {
  const overview = usePoll<EngineOverview>("/api/engine/overview", 2000);
  return <OverviewContext.Provider value={overview}>{children}</OverviewContext.Provider>;
}

export function useOverview(): Poll<EngineOverview> {
  const value = useContext(OverviewContext);
  if (!value) throw new Error("useOverview must be used inside OverviewProvider");
  return value;
}
