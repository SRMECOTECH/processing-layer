"use client";

import { createContext, useContext, type ReactNode } from "react";
import type { EngineOverview } from "./types";
import { usePoll, type Poll } from "./use-poll";

const OverviewContext = createContext<Poll<EngineOverview> | null>(null);

/**
 * One shared poll of /api/engine/overview for the header and every page. Off (enabled = false) for transporters and
 * consigners: the engine is the GPS provider's business, and its API is not theirs to call.
 */
export function OverviewProvider({ children, enabled = true }: { children: ReactNode; enabled?: boolean }) {
  const overview = usePoll<EngineOverview>(enabled ? "/api/engine/overview" : null, 2000);
  return <OverviewContext.Provider value={overview}>{children}</OverviewContext.Provider>;
}

export function useOverview(): Poll<EngineOverview> {
  const value = useContext(OverviewContext);
  if (!value) throw new Error("useOverview must be used inside OverviewProvider");
  return value;
}
