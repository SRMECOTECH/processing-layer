"use client";

import { Suspense } from "react";
import { ShipmentsPage } from "@/components/tms/shipments";

// The shipments the transporter carries.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ShipmentsPage title="My shipments" />
    </Suspense>
  );
}
