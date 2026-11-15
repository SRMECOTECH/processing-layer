"use client";

import { PortalGeofenceReport } from "@/components/geofence/portal-report";

// The consigner's geofence day report: the trucks carrying its goods that week.
export default function Page() {
  return (
    <PortalGeofenceReport description="How long the trucks carrying your goods waited at your plant and at your consignees today, against what is usual at each place." />
  );
}
