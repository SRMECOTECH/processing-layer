"use client";

import { Suspense } from "react";
import { PlatformOverview } from "@/components/tms/overview";

// The GPS provider's TMS overview: every company.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <PlatformOverview />
    </Suspense>
  );
}
