"use client";

import { Suspense } from "react";
import { ShipmentDetailPage } from "@/components/tms/shipment-detail";

// One shipment: /tms/shipments/view?id=...
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ShipmentDetailPage />
    </Suspense>
  );
}
