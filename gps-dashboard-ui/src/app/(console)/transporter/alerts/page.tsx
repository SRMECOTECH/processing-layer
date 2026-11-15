"use client";

import { Suspense } from "react";
import { AlertsPage } from "@/components/tms/fleet";

// The transporter's alerts.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <AlertsPage description="Over-speed, stoppages, harsh driving and delays on your own trucks, and on hired trucks while they carry your shipments." />
    </Suspense>
  );
}
