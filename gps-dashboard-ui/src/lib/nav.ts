import type { LucideIcon } from "lucide-react";
import {
  Activity,
  Car,
  ChartLine,
  Cpu,
  Crosshair,
  Database,
  DatabaseZap,
  FileCheck,
  Gauge,
  Grid3x3,
  ListChecks,
  Network,
  Power,
  Route,
  ScanLine,
  Server,
  ShieldCheck,
  Siren,
  Table2,
  Terminal,
  Timer,
  TriangleAlert,
  Truck,
  Waypoints,
  Workflow,
} from "lucide-react";

export type NavItem = { href: string; label: string; icon: LucideIcon };
export type NavGroup = { title: string; items: NavItem[] };
export type Section = { href: string; label: string; groups: NavGroup[] };

/**
 * The whole navigation: top tabs (sections) and, per section, the left menu (groups of sub-pages).
 * Each sub-page shows one or two cards, so a section can grow by adding sub-pages, not by crowding one page.
 */
export const SECTIONS: Section[] = [
  {
    href: "/stream",
    label: "Stream Processors",
    groups: [
      {
        title: "Pipeline",
        items: [
          { href: "/stream", label: "Pipeline overview", icon: Workflow },
          { href: "/stream/tracking", label: "Vehicle Tracking (Cache)", icon: Crosshair },
          { href: "/stream/vehicle-state", label: "Vehicle State (Kafka Context)", icon: Car },
        ],
      },
      {
        title: "Stream processing",
        items: [
          { href: "/stream/speed-violation", label: "Speed Violation (Redis/Haversine)", icon: Gauge },
          { href: "/stream/stoppage-ignition", label: "Stoppage & Ignition (AI Traffic)", icon: Power },
        ],
      },
      {
        title: "Spatial",
        items: [
          { href: "/stream/geofencing", label: "Nested Geofencing", icon: Grid3x3 },
          { href: "/stream/waypoints", label: "Waypoints & Corridors", icon: Waypoints },
          { href: "/stream/kalman", label: "Kalman Filter Distance", icon: Activity },
        ],
      },
    ],
  },
  {
    href: "/rules",
    label: "Business Rules & Alerts",
    groups: [
      {
        title: "Business logics",
        items: [
          { href: "/rules", label: "Over-speed Verification", icon: ShieldCheck },
          { href: "/rules/trips", label: "Trip Detection Master", icon: Route },
          { href: "/rules/route-deviation", label: "Route Deviation", icon: TriangleAlert },
        ],
      },
      {
        title: "Alerting",
        items: [
          { href: "/rules/dispatch", label: "Dispatch & Escalations", icon: Siren },
          { href: "/rules/audit-queue", label: "Ingest Audit Queue", icon: ListChecks },
        ],
      },
    ],
  },
  {
    href: "/ingestion",
    label: "Ingestion & DB Sink",
    groups: [
      {
        title: "Storage ingestion",
        items: [
          { href: "/ingestion", label: "Schema Validation Gate", icon: FileCheck },
          { href: "/ingestion/postgresql", label: "PostgreSQL Sink", icon: Database },
          { href: "/ingestion/clickhouse", label: "ClickHouse OLAP Sink", icon: Table2 },
        ],
      },
      {
        title: "Workers",
        items: [{ href: "/ingestion/workers", label: "Worker Health & Backpressure", icon: Cpu }],
      },
    ],
  },
  {
    href: "/analytics",
    label: "Telemetry Analytics",
    groups: [
      {
        title: "Analytics",
        items: [
          { href: "/analytics", label: "Live Stream Telemetry", icon: ChartLine },
          { href: "/analytics/latency", label: "Latency & Backpressure", icon: Timer },
        ],
      },
      {
        title: "Insights",
        items: [
          { href: "/analytics/anomalies", label: "Violations & Anomalies", icon: ScanLine },
          { href: "/analytics/trips", label: "Vehicle State & Trip Efficiency", icon: Truck },
        ],
      },
    ],
  },
  {
    href: "/developer",
    label: "Developer",
    groups: [
      {
        title: "Developer",
        items: [
          { href: "/developer", label: "Engine overview", icon: Terminal },
          { href: "/developer/processors", label: "Producers & consumers", icon: Network },
          { href: "/developer/rules", label: "Trips, stops & readings", icon: Route },
          { href: "/developer/ingestion", label: "Topics & storage", icon: Server },
          { href: "/developer/demo", label: "Demo tools (Empty DB)", icon: DatabaseZap },
        ],
      },
    ],
  },
];

export function sectionFor(pathname: string): Section {
  return SECTIONS.find((s) => pathname === s.href || pathname.startsWith(`${s.href}/`)) ?? SECTIONS[0];
}

export function allPages(): (NavItem & { section: string })[] {
  return SECTIONS.flatMap((s) => s.groups.flatMap((g) => g.items.map((item) => ({ ...item, section: s.label }))));
}

/** Quick filters shown above the operational pages; each jumps to the sub-page that owns it. */
export const QUICK_FILTERS: { href: string; label: string }[] = [
  { href: "/stream", label: "All Processors" },
  { href: "/stream/speed-violation", label: "Speed & Lat/Lon" },
  { href: "/stream/stoppage-ignition", label: "Ignition/Stoppage" },
  { href: "/stream/geofencing", label: "Geofencing" },
  { href: "/stream/kalman", label: "Kalman Filter" },
  { href: "/ingestion/postgresql", label: "Storage Sinks" },
];
