"use client";

import { Suspense } from "react";
import { PerformancePage } from "@/components/tms/performance";

// Scorecards: transporters, lanes, consignees, plants.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <PerformancePage />
    </Suspense>
  );
}
