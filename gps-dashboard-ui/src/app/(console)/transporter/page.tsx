"use client";

import { Suspense } from "react";
import { TransporterOverview } from "@/components/tms/overview";

// A transporter's home: its trucks and the shipments it carries today.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <TransporterOverview />
    </Suspense>
  );
}
