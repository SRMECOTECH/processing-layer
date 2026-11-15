"use client";

import { Suspense } from "react";
import { PlacesPage } from "@/components/tms/admin";

// Places and geofences.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <PlacesPage />
    </Suspense>
  );
}
