import type { LucideIcon } from "lucide-react";
import {
  Activity,
  Bell,
  BellRing,
  Boxes,
  Braces,
  Car,
  ChartLine,
  Cpu,
  Crosshair,
  Database,
  DatabaseZap,
  FileCheck,
  FileX,
  Gauge,
  GitCompareArrows,
  Grid3x3,
  History,
  Inbox,
  Layers,
  ListChecks,
  Map,
  MapPinned,
  Moon,
  Network,
  Play,
  Power,
  Radio,
  Route,
  ScanLine,
  Send,
  Server,
  ShieldCheck,
  Siren,
  SlidersHorizontal,
  Table2,
  Terminal,
  Timer,
  TriangleAlert,
  Truck,
  Waypoints,
  Workflow,
} from "lucide-react";

/** Where a page's numbers come from, shown as a small mark in the menu. */
export type Feed = "live" | "partial" | "planned";

export type NavLeaf = { href: string; label: string; feed?: Feed };
export type NavItem = { href: string; label: string; icon: LucideIcon; feed?: Feed; children?: NavLeaf[] };
export type NavGroup = { title: string; items: NavItem[] };
export type Section = { href: string; label: string; icon: LucideIcon; description: string; groups: NavGroup[] };

/**
 * The whole navigation. Sections are the fixed top bar; each section has its own left menu of groups,
 * and an item in a group may open into sub-pages (the subdivisions). Add a page here and create its page.tsx.
 */
export const SECTIONS: Section[] = [
  {
    href: "/live",
    label: "Live",
    icon: Radio,
    description: "What the fleet and the stream are doing right now.",
    groups: [
      {
        title: "Overview",
        items: [
          { href: "/live", label: "Fleet overview", icon: Activity, feed: "live" },
          { href: "/live/map", label: "Live map", icon: Map, feed: "live" },
        ],
      },
      {
        title: "Vehicles",
        items: [
          { href: "/live/vehicles", label: "Vehicle list", icon: Car, feed: "live" },
          { href: "/live/inspector", label: "Vehicle inspector", icon: Crosshair, feed: "live" },
        ],
      },
      {
        title: "Stream",
        items: [
          { href: "/live/throughput", label: "Throughput", icon: ChartLine, feed: "live" },
          { href: "/live/events", label: "Event stream", icon: ListChecks, feed: "live" },
          { href: "/live/simulator", label: "Fleet simulator", icon: Play, feed: "live" },
        ],
      },
    ],
  },
  {
    href: "/processing",
    label: "Processing",
    icon: Workflow,
    description: "Parallel stream processors reading the Kafka output topic.",
    groups: [
      {
        title: "Pipeline",
        items: [
          { href: "/processing", label: "Pipeline overview", icon: Workflow, feed: "live" },
          { href: "/processing/consumers", label: "Consumer groups", icon: Network, feed: "live" },
          {
            href: "/processing/streams",
            label: "Kafka Streams",
            icon: Layers,
            feed: "live",
            children: [
              { href: "/processing/streams", label: "Topology & tasks", feed: "live" },
              { href: "/processing/streams/table", label: "Vehicle table", feed: "live" },
              { href: "/processing/streams/windows", label: "Activity windows", feed: "live" },
              { href: "/processing/streams/parity", label: "Shadow parity", feed: "live" },
            ],
          },
        ],
      },
      {
        title: "Processors",
        items: [
          {
            href: "/processing/speed",
            label: "Speed violation",
            icon: Gauge,
            feed: "live",
            children: [
              { href: "/processing/speed", label: "Live episodes", feed: "live" },
              { href: "/processing/speed/rules", label: "Entity rules", feed: "live" },
              { href: "/processing/speed/cross-check", label: "Distance cross-check", feed: "live" },
              { href: "/processing/speed/events", label: "Events", feed: "live" },
            ],
          },
          {
            href: "/processing/state",
            label: "Vehicle state",
            icon: Car,
            feed: "live",
            children: [
              { href: "/processing/state", label: "State store", feed: "live" },
              { href: "/processing/state/history", label: "Historical context", feed: "live" },
              { href: "/processing/state/keys", label: "Key layout", feed: "live" },
            ],
          },
          {
            href: "/processing/stoppage",
            label: "Stoppage & ignition",
            icon: Power,
            feed: "live",
            children: [
              { href: "/processing/stoppage", label: "Live stoppages", feed: "live" },
              { href: "/processing/stoppage/ignition-on", label: "Ignition on · rules", feed: "live" },
              { href: "/processing/stoppage/ignition-off", label: "Ignition off · neutralised", feed: "live" },
              { href: "/processing/stoppage/agents", label: "Traffic & weather agents", feed: "live" },
            ],
          },
          {
            href: "/processing/geofence",
            label: "Geofencing",
            icon: Grid3x3,
            feed: "planned",
            children: [
              { href: "/processing/geofence", label: "Zone hierarchy", feed: "planned" },
              { href: "/processing/geofence/distance", label: "Nested distance", feed: "planned" },
              { href: "/processing/geofence/events", label: "Entry & exit", feed: "planned" },
            ],
          },
          {
            href: "/processing/waypoints",
            label: "Waypoints",
            icon: Waypoints,
            feed: "planned",
            children: [
              { href: "/processing/waypoints", label: "Routes & waypoints", feed: "planned" },
              { href: "/processing/waypoints/arrivals", label: "Arrivals & ETA", feed: "planned" },
            ],
          },
          {
            href: "/processing/distance",
            label: "Cumulative distance",
            icon: Activity,
            feed: "live",
            children: [
              { href: "/processing/distance", label: "Kalman vs raw", feed: "live" },
              { href: "/processing/distance/trips", label: "Trip increments", feed: "live" },
              { href: "/processing/distance/tuning", label: "Filter tuning", feed: "live" },
            ],
          },
        ],
      },
    ],
  },
  {
    href: "/alerts",
    label: "Alerts",
    icon: Siren,
    description: "Business logic that turns processed readings into alerts.",
    groups: [
      {
        title: "Alert center",
        items: [
          { href: "/alerts", label: "All alerts", icon: Inbox, feed: "live" },
          { href: "/alerts/escalations", label: "Escalations", icon: BellRing, feed: "partial" },
        ],
      },
      {
        title: "Business logic",
        items: [
          {
            href: "/alerts/overspeed",
            label: "Over-speed",
            icon: ShieldCheck,
            feed: "live",
            children: [
              { href: "/alerts/overspeed", label: "Dual verification", feed: "live" },
              { href: "/alerts/overspeed/audit", label: "Device log audit", feed: "live" },
              { href: "/alerts/overspeed/policy", label: "Alert policy", feed: "planned" },
            ],
          },
          {
            href: "/alerts/trips",
            label: "Trip detection",
            icon: Route,
            feed: "live",
            children: [
              { href: "/alerts/trips", label: "Trips", feed: "live" },
              { href: "/alerts/trips/master", label: "Master table lookup", feed: "live" },
              { href: "/alerts/trips/stops", label: "Stops", feed: "live" },
            ],
          },
          {
            href: "/alerts/route-deviation",
            label: "Route deviation",
            icon: TriangleAlert,
            feed: "planned",
            children: [
              { href: "/alerts/route-deviation", label: "Corridors", feed: "planned" },
              { href: "/alerts/route-deviation/log", label: "Deviation log", feed: "planned" },
            ],
          },
        ],
      },
    ],
  },
  {
    href: "/storage",
    label: "Storage",
    icon: Database,
    description: "Ingestion control: schema validation, then PostgreSQL and ClickHouse.",
    groups: [
      {
        title: "Ingestion control",
        items: [
          { href: "/storage", label: "Ingestion overview", icon: Workflow, feed: "live" },
          { href: "/storage/schema", label: "Schema validation", icon: FileCheck, feed: "live" },
          { href: "/storage/rejected", label: "Rejected frames", icon: FileX, feed: "live" },
        ],
      },
      {
        title: "Sinks",
        items: [
          { href: "/storage/postgres", label: "PostgreSQL", icon: Database, feed: "live" },
          { href: "/storage/clickhouse", label: "ClickHouse", icon: Table2, feed: "planned" },
        ],
      },
      {
        title: "Operations",
        items: [{ href: "/storage/workers", label: "Workers & backpressure", icon: Cpu, feed: "live" }],
      },
    ],
  },
  {
    href: "/analytics",
    label: "Analytics",
    icon: ChartLine,
    description: "Trends across the pipeline and the fleet.",
    groups: [
      {
        title: "Pipeline",
        items: [
          { href: "/analytics", label: "Live telemetry", icon: ChartLine, feed: "live" },
          { href: "/analytics/latency", label: "Latency & backpressure", icon: Timer, feed: "live" },
        ],
      },
      {
        title: "Fleet",
        items: [
          { href: "/analytics/anomalies", label: "Violations & anomalies", icon: ScanLine, feed: "live" },
          { href: "/analytics/trips", label: "Trip efficiency", icon: Truck, feed: "live" },
        ],
      },
    ],
  },
  {
    href: "/notifications",
    label: "Notifications",
    icon: Bell,
    description: "Who hears about what, and through which channel.",
    groups: [
      {
        title: "Inbox",
        items: [{ href: "/notifications", label: "All notifications", icon: Inbox, feed: "live" }],
      },
      {
        title: "Delivery",
        items: [
          { href: "/notifications/channels", label: "Channels", icon: Send, feed: "planned" },
          { href: "/notifications/rules", label: "Routing rules", icon: GitCompareArrows, feed: "planned" },
          { href: "/notifications/log", label: "Delivery log", icon: History, feed: "planned" },
        ],
      },
      {
        title: "Preferences",
        items: [{ href: "/notifications/preferences", label: "Quiet hours & digest", icon: Moon, feed: "planned" }],
      },
    ],
  },
  {
    href: "/developer",
    label: "Developer",
    icon: Terminal,
    description: "Engine internals for the people building the platform.",
    groups: [
      {
        title: "Engine",
        items: [
          { href: "/developer", label: "Engine overview", icon: Terminal },
          { href: "/developer/processors", label: "Producers & consumers", icon: Network },
          { href: "/developer/ingestion", label: "Topics & storage", icon: Server },
        ],
      },
      {
        title: "Data",
        items: [
          { href: "/developer/rules", label: "Trips, stops & readings", icon: MapPinned },
          { href: "/developer/api", label: "API reference", icon: Braces },
        ],
      },
      {
        title: "Tools",
        items: [
          { href: "/developer/demo", label: "Demo tools (Empty DB)", icon: DatabaseZap },
          { href: "/developer/settings", label: "Service settings", icon: SlidersHorizontal },
          { href: "/developer/modules", label: "Module registry", icon: Boxes },
        ],
      },
    ],
  },
];

export function sectionFor(pathname: string): Section {
  return SECTIONS.find((s) => pathname === s.href || pathname.startsWith(`${s.href}/`)) ?? SECTIONS[0];
}

/** Whether a menu item (or one of its sub-pages) is the page being shown. */
export function itemContains(item: NavItem, pathname: string): boolean {
  return item.href === pathname || (item.children ?? []).some((c) => c.href === pathname);
}

export type Trail = { section: Section; group?: NavGroup; item?: NavItem; leaf?: NavLeaf };

/** Section, menu item and sub-page of a path: the breadcrumb. */
export function trailFor(pathname: string): Trail {
  const section = sectionFor(pathname);
  for (const group of section.groups) {
    for (const item of group.items) {
      const leaf = item.children?.find((c) => c.href === pathname);
      if (leaf) return { section, group, item, leaf };
      if (item.href === pathname) return { section, group, item };
    }
  }
  return { section };
}

export type PageEntry = { href: string; label: string; section: string; parent?: string; icon: LucideIcon; feed?: Feed };

/** Every page once, for search. A sub-page carries the name of the item it belongs to. */
export function allPages(): PageEntry[] {
  return SECTIONS.flatMap((s) =>
    s.groups.flatMap((g) =>
      g.items.flatMap((item) =>
        item.children
          ? item.children.map((c) => ({ href: c.href, label: c.label, section: s.label, parent: item.label, icon: item.icon, feed: c.feed }))
          : [{ href: item.href, label: item.label, section: s.label, icon: item.icon, feed: item.feed }],
      ),
    ),
  );
}
