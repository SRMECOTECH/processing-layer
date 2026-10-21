"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { alertsFrom } from "./alerts";
import { vehicleName } from "./format";
import { useOverview } from "./overview-context";
import type { VehicleEventView } from "./types";
import { usePoll } from "./use-poll";

export type Notice = {
  id: string;
  kind: "platform" | "vehicle";
  tone: "bad" | "warn" | "info";
  title: string;
  text: string;
  href: string;
  at: string | null;
};

type Notifications = {
  notices: Notice[];
  unread: number;
  isRead: (id: string) => boolean;
  markRead: (id: string) => void;
  markAllRead: () => void;
  /** Open high and critical vehicle events: the count on the Alerts tab. */
  openAlerts: number;
};

const STORAGE_KEY = "pulsestream.read-notices";
const KEEP = 400;
const NotificationsContext = createContext<Notifications | null>(null);

const EVENT_TITLE: Record<string, string> = {
  OVERSPEED: "Over-speed",
  SPEED_SENSOR_MISMATCH: "Speedometer disagrees with GPS",
  STOPPAGE: "Unexplained stoppage",
  IDLING: "Idling",
  HARSH_ACCELERATION: "Harsh acceleration",
  HARSH_BRAKING: "Harsh braking",
};

/**
 * One list of what an operator should hear about: platform problems worked out from the engine overview,
 * and high or critical vehicle events. Which ones were read is remembered in this browser only.
 */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { data: overview } = useOverview();
  const events = usePoll<VehicleEventView[]>("/api/events?limit=60", 10000);
  const [read, setRead] = useState<Set<string>>(new Set());

  useEffect(() => {
    try {
      setRead(new Set(JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "[]") as string[]));
    } catch {
      // Unreadable: everything shows as unread.
    }
  }, []);

  const save = useCallback((next: Set<string>) => {
    setRead(next);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify([...next].slice(-KEEP)));
    } catch {
      // Not remembered after a reload.
    }
  }, []);

  const notices = useMemo<Notice[]>(() => {
    const platform: Notice[] = alertsFrom(overview).map((a) => ({
      id: `platform:${a.title}`,
      kind: "platform",
      tone: a.tone,
      title: a.title,
      text: a.text,
      href: a.href,
      at: overview?.generatedAt ?? null,
    }));
    const vehicle: Notice[] = (events.data ?? [])
      .filter((e) => e.severity === "HIGH" || e.severity === "CRITICAL")
      .map((e) => ({
        id: `event:${e.id}`,
        kind: "vehicle",
        tone: e.severity === "CRITICAL" ? "bad" : "warn",
        title: `${EVENT_TITLE[e.type] ?? e.type} · ${vehicleName(e.vehicleRegistrationNo, e.imei)}`,
        text: e.status === "OPEN" ? "Ongoing" : "Ended",
        href: `/live/inspector?imei=${e.imei}`,
        at: e.startedAt,
      }));
    return [...platform, ...vehicle];
  }, [overview, events.data]);

  const openAlerts = (events.data ?? []).filter((e) => e.status === "OPEN" && (e.severity === "HIGH" || e.severity === "CRITICAL")).length;
  const unread = notices.filter((n) => !read.has(n.id)).length;

  const value: Notifications = {
    notices,
    unread,
    openAlerts,
    isRead: (id) => read.has(id),
    markRead: (id) => {
      if (!read.has(id)) save(new Set(read).add(id));
    },
    markAllRead: () => save(new Set([...read, ...notices.map((n) => n.id)])),
  };

  return <NotificationsContext.Provider value={value}>{children}</NotificationsContext.Provider>;
}

export function useNotifications(): Notifications {
  const value = useContext(NotificationsContext);
  if (!value) throw new Error("useNotifications must be used inside NotificationsProvider");
  return value;
}
