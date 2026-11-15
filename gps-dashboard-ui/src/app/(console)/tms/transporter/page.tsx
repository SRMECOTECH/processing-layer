"use client";

import { Suspense } from "react";
import { TransporterOverview } from "@/components/tms/overview";

// What a transporter sees, for any transporter.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <TransporterOverview asDeveloper />
    </Suspense>
  );
}
