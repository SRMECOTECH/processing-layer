"use client";

import { Suspense } from "react";
import { ConsignerOverview } from "@/components/tms/overview";

// What a consigner sees, for any consigner.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <ConsignerOverview asDeveloper />
    </Suspense>
  );
}
