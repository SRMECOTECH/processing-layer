"use client";

import { Suspense } from "react";
import { MappingPage } from "@/components/tms/admin";

// Device <-> vehicle mapping.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <MappingPage />
    </Suspense>
  );
}
