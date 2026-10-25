import type { NextConfig } from "next";

// Pages of the earlier layout, so old bookmarks and links still land on the right page.
const MOVED: [string, string][] = [
  ["/stream", "/processing"],
  ["/stream/tracking", "/live/inspector"],
  ["/stream/vehicle-state", "/live/vehicles"],
  ["/stream/speed-violation", "/processing/speed"],
  ["/stream/stoppage-ignition", "/processing/stoppage"],
  ["/stream/geofencing", "/processing/geofence"],
  ["/stream/waypoints", "/processing/waypoints"],
  ["/stream/kalman", "/processing/distance"],
  ["/rules", "/alerts/overspeed"],
  ["/rules/trips", "/alerts/trips"],
  ["/rules/route-deviation", "/alerts/route-deviation"],
  ["/rules/dispatch", "/alerts/escalations"],
  ["/rules/audit-queue", "/alerts/overspeed/audit"],
  ["/ingestion", "/storage/schema"],
  ["/ingestion/postgresql", "/storage/postgres"],
  ["/ingestion/clickhouse", "/storage/clickhouse"],
  ["/ingestion/workers", "/storage/workers"],
];

// "standalone" builds a small self-contained server for the Docker image (see Dockerfile).
// Calls to the GPS service go through src/app/api/[...path]/route.ts, so the service address
// (GPS_API_URL) is read when the server starts, not baked in at build time.
const nextConfig: NextConfig = {
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
  async redirects() {
    return MOVED.map(([source, destination]) => ({ source, destination, permanent: false }));
  },
};

export default nextConfig;
