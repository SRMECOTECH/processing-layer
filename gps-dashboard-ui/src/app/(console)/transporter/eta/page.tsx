"use client";

import { PortalEta } from "@/components/routes/portal-eta";

// The transporter's trucks on their planned roads: off route, wrong way, live ETA against the promise.
export default function Page() {
  return (
    <PortalEta description="Every trip of your trucks on its planned road: where each truck is, whether it left the route or is driving the wrong way, and when it will reach the customer against the ETA promised when it left." />
  );
}
