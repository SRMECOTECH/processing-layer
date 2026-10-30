import type { IconComponent } from "@/components/icons";
import type { Role } from "./session";
import {
  Activity,
  Archive,
  Bell,
  BellRing,
  Boxes,
  Braces,
  Building2,
  CalendarClock,
  CalendarDays,
  Car,
  ChartLine,
  Cpu,
  Crosshair,
  Database,
  DatabaseZap,
  FileCheck,
  FileText,
  FileX,
  Gauge,
  GitCompareArrows,
  Grid3x3,
  History,
  Inbox,
  Layers,
  Link2,
  LayoutGrid,
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
  Settings2,
  ShieldCheck,
  Sigma,
  Siren,
  SlidersHorizontal,
  Table2,
  Terminal,
  Timer,
  TriangleAlert,
  Truck,
  Upload,
  Users,
  Waypoints,
  Workflow,
} from "@/components/icons";

/** Where a page's numbers come from, shown as a small mark in the menu. */
export type Feed = "live" | "partial" | "planned";

export type NavLeaf = { href: string; label: string; feed?: Feed };
export type NavItem = { href: string; label: string; icon: IconComponent; feed?: Feed; children?: NavLeaf[] };
export type NavGroup = { title: string; items: NavItem[] };
export type Section = { href: string; label: string; icon: IconComponent; description: string; groups: NavGroup[] };

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
    href: "/tms",
    label: "TMS",
    icon: Truck,
    description: "Transport management: shipments, transporters, consigners and the device ↔ truck mapping.",
    groups: [
      {
        title: "Shipments",
        items: [
          { href: "/tms", label: "TMS overview", icon: LayoutGrid, feed: "live" },
          { href: "/tms/shipments", label: "All shipments", icon: Route, feed: "live" },
          { href: "/tms/upload", label: "Upload shipments", icon: Upload, feed: "live" },
        ],
      },
      {
        title: "Customer views",
        items: [
          { href: "/tms/transporter", label: "As a transporter", icon: Truck, feed: "live" },
          { href: "/tms/consigner", label: "As a consigner", icon: Building2, feed: "live" },
        ],
      },
      {
        title: "Master data",
        items: [
          { href: "/tms/mapping", label: "Device ↔ vehicle mapping", icon: Link2, feed: "live" },
          { href: "/tms/companies", label: "Companies & users", icon: Users, feed: "live" },
          { href: "/tms/places", label: "Places & geofences", icon: MapPinned, feed: "live" },
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
    href: "/reports",
    label: "Reports",
    icon: FileText,
    description: "End-of-day and end-of-month reports for every vehicle: stops, idling, distance, fuel and money.",
    groups: [
      {
        title: "Reports",
        items: [
          { href: "/reports", label: "Day report", icon: CalendarClock, feed: "live" },
          { href: "/reports/month", label: "Month report", icon: CalendarDays, feed: "live" },
          { href: "/reports/vehicle", label: "Vehicle day sheet", icon: Truck, feed: "live" },
        ],
      },
      {
        title: "Business value",
        items: [{ href: "/reports/savings", label: "Fuel & savings", icon: Sigma, feed: "live" }],
      },
      {
        title: "Setup",
        items: [
          { href: "/reports/assumptions", label: "Cost assumptions", icon: Settings2, feed: "live" },
          { href: "/reports/clear", label: "Clear database", icon: DatabaseZap, feed: "live" },
        ],
      },
    ],
  },
  {
    href: "/storage",
    label: "Storage",
    icon: Database,
    description: "Ingestion control, no data loss, then PostgreSQL and ClickHouse.",
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
        title: "No data loss",
        items: [
          { href: "/storage/safety", label: "Data safety", icon: ShieldCheck, feed: "live" },
          { href: "/storage/dead-letters", label: "Dead letters", icon: Archive, feed: "live" },
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
          { href: "/developer/connections", label: "Connections", icon: Link2 },
          { href: "/developer/settings", label: "Service settings", icon: SlidersHorizontal },
          { href: "/developer/modules", label: "Module registry", icon: Boxes },
        ],
      },
    ],
  },
];

/** A transporter's portal: its trucks, the shipments it carries, and what happened on the road. */
export const TRANSPORTER_SECTIONS: Section[] = [
  {
    href: "/transporter",
    label: "Overview",
    icon: LayoutGrid,
    description: "Your fleet and your shipments today.",
    groups: [{ title: "Today", items: [{ href: "/transporter", label: "Overview", icon: LayoutGrid, feed: "live" }] }],
  },
  {
    href: "/transporter/fleet",
    label: "Fleet",
    icon: Truck,
    description: "Every truck you own or carry shipments with, live.",
    groups: [{ title: "Fleet", items: [{ href: "/transporter/fleet", label: "Fleet board", icon: Truck, feed: "live" }] }],
  },
  {
    href: "/transporter/shipments",
    label: "Shipments",
    icon: Route,
    description: "The trips you carry, from gate-out to the consignee.",
    groups: [
      {
        title: "Shipments",
        items: [
          { href: "/transporter/shipments", label: "My shipments", icon: Route, feed: "live" },
          { href: "/transporter/shipments/upload", label: "Upload shipments", icon: Upload, feed: "live" },
        ],
      },
    ],
  },
  {
    href: "/transporter/alerts",
    label: "Alerts",
    icon: Siren,
    description: "Over-speed, stoppages, delays: on your trucks and the trips you carry.",
    groups: [{ title: "Alerts", items: [{ href: "/transporter/alerts", label: "Alerts", icon: Siren, feed: "live" }] }],
  },
];

/** A consigner's portal: its goods on the road, and how the transporters carrying them perform. */
export const CONSIGNER_SECTIONS: Section[] = [
  {
    href: "/consigner",
    label: "Overview",
    icon: LayoutGrid,
    description: "Your shipments on the road today.",
    groups: [{ title: "Today", items: [{ href: "/consigner", label: "Overview", icon: LayoutGrid, feed: "live" }] }],
  },
  {
    href: "/consigner/shipments",
    label: "Shipments",
    icon: Route,
    description: "Every shipment from your plants, gate-out to consignee.",
    groups: [{ title: "Shipments", items: [{ href: "/consigner/shipments", label: "My shipments", icon: Route, feed: "live" }] }],
  },
  {
    href: "/consigner/performance",
    label: "Performance",
    icon: ChartLine,
    description: "Transporters, lanes, consignees and plant detention.",
    groups: [{ title: "Performance", items: [{ href: "/consigner/performance", label: "Scorecards", icon: ChartLine, feed: "live" }] }],
  },
  {
    href: "/consigner/alerts",
    label: "Alerts",
    icon: Siren,
    description: "What happened to your goods on the road.",
    groups: [{ title: "Alerts", items: [{ href: "/consigner/alerts", label: "Alerts", icon: Siren, feed: "live" }] }],
  },
];

/** The top-bar sections a role sees: the GPS provider the whole console, the others their portal. */
export function sectionsFor(role: Role): Section[] {
  return role === "TRANSPORTER" ? TRANSPORTER_SECTIONS : role === "CONSIGNER" ? CONSIGNER_SECTIONS : SECTIONS;
}

/** The section a page belongs to: the longest section path it starts with ("/transporter/fleet" before "/transporter"). */
export function sectionFor(pathname: string, sections: Section[] = SECTIONS): Section {
  let best: Section | undefined;
  for (const s of sections) {
    if ((pathname === s.href || pathname.startsWith(`${s.href}/`)) && (!best || s.href.length > best.href.length)) best = s;
  }
  return best ?? sections[0];
}

/** Whether a menu item (or one of its sub-pages) is the page being shown. */
export function itemContains(item: NavItem, pathname: string): boolean {
  return item.href === pathname || (item.children ?? []).some((c) => c.href === pathname);
}

export type Trail = { section: Section; group?: NavGroup; item?: NavItem; leaf?: NavLeaf };

/** Section, menu item and sub-page of a path: the breadcrumb. */
export function trailFor(pathname: string, sections: Section[] = SECTIONS): Trail {
  const section = sectionFor(pathname, sections);
  for (const group of section.groups) {
    for (const item of group.items) {
      const leaf = item.children?.find((c) => c.href === pathname);
      if (leaf) return { section, group, item, leaf };
      if (item.href === pathname) return { section, group, item };
    }
  }
  return { section };
}

export type PageEntry = { href: string; label: string; section: string; parent?: string; icon: IconComponent; feed?: Feed };

/** Every page once, for search. A sub-page carries the name of the item it belongs to. */
export function allPages(sections: Section[] = SECTIONS): PageEntry[] {
  return sections.flatMap((s) =>
    s.groups.flatMap((g) =>
      g.items.flatMap((item) =>
        item.children
          ? item.children.map((c) => ({ href: c.href, label: c.label, section: s.label, parent: item.label, icon: item.icon, feed: c.feed }))
          : [{ href: item.href, label: item.label, section: s.label, icon: item.icon, feed: item.feed }],
      ),
    ),
  );
}
