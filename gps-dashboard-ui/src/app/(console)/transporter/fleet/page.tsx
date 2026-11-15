"use client";

import { Suspense } from "react";
import { FleetBoard } from "@/components/tms/fleet";

// Every truck of the transporter, live.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <FleetBoard />
    </Suspense>
  );
}
