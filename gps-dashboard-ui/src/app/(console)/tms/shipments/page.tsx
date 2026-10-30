"use client";

import { Suspense } from "react";
import { ShipmentsPage } from "@/components/tms/shipments";

// Every shipment on the platform.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ShipmentsPage title="All shipments" />
    </Suspense>
  );
}
