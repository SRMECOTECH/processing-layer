"use client";

import { Suspense } from "react";
import { ImportPanel } from "@/components/tms/import";

// The transporter uploads its shipment file.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ImportPanel />
    </Suspense>
  );
}
