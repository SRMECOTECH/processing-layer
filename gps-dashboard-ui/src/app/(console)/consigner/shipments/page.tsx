"use client";

import { Suspense } from "react";
import { ShipmentsPage } from "@/components/tms/shipments";

// The consigner's shipments.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ShipmentsPage title="My shipments" description="Every shipment from your plants, followed by the truck's GPS from the gate-out to the consignee. You see a truck only while it carries your goods." />
    </Suspense>
  );
}
