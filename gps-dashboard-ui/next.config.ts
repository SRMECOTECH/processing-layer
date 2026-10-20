import type { NextConfig } from "next";

// "standalone" builds a small self-contained server for the Docker image (see Dockerfile).
// Calls to the GPS service go through src/app/api/[...path]/route.ts, so the service address
// (GPS_API_URL) is read when the server starts, not baked in at build time.
const nextConfig: NextConfig = {
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
};

export default nextConfig;
