"use client";

import { Suspense } from "react";
import { CompaniesPage } from "@/components/tms/admin";

// Transporters, consigners and their users.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <CompaniesPage />
    </Suspense>
  );
}
