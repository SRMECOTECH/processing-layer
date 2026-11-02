"use client";

import { PortalGeofenceReport } from "@/components/geofence/portal-report";

// The transporter's geofence day report: its own trucks, and hired trucks while they carry its shipments.
export default function Page() {
  return (
    <PortalGeofenceReport description="Where your trucks spent the day: time at the plant, at each customer and in yards, which stays were far longer than usual at that fence, and which trucks are still held." />
  );
}
