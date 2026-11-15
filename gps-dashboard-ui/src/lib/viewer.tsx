"use client";

import { createContext, useContext, type ReactNode } from "react";
import { sectionsFor, type Section } from "./nav";
import type { Role } from "./session";

/** Who is looking: the signed-in user's role and company, from the session (never the token itself). */
export type Viewer = { user: string; role: Role; name?: string; tenant?: string; tenantName?: string };

const ViewerContext = createContext<Viewer>({ user: "", role: "DEVELOPER" });

export function ViewerProvider({ viewer, children }: { viewer: Viewer; children: ReactNode }) {
  return <ViewerContext.Provider value={viewer}>{children}</ViewerContext.Provider>;
}

export function useViewer(): Viewer {
  return useContext(ViewerContext);
}

/** The top-bar sections of the viewer's role. */
export function useSections(): Section[] {
  return sectionsFor(useViewer().role);
}

/** Where a shipment's page is for this role. */
export function shipmentHref(role: Role, id: number): string {
  const base = role === "TRANSPORTER" ? "/transporter" : role === "CONSIGNER" ? "/consigner" : "/tms";
  return `${base}/shipments/view?id=${id}`;
}
