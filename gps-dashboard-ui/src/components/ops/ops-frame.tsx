import type { ReactNode } from "react";
import { Offline, QuickFilters } from "./kit";

/** Shared top of every operational page: quick filters and the offline banner. */
export function OpsFrame({ children }: { children: ReactNode }) {
  return (
    <>
      <QuickFilters />
      <Offline />
      {children}
    </>
  );
}
