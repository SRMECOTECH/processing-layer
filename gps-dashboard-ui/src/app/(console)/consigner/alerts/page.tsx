"use client";

import { Suspense } from "react";
import { AlertsPage } from "@/components/tms/fleet";

// The consigner's alerts.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <AlertsPage description="What happened while trucks carried your goods: stoppages, over-speed, departures, arrivals and delays. Nothing from before the gate-out or after the arrival." />
    </Suspense>
  );
}
