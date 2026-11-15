"use client";

import { Suspense } from "react";
import { ConsignerOverview } from "@/components/tms/overview";

// A consigner's home: its goods on the road.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ConsignerOverview />
    </Suspense>
  );
}
