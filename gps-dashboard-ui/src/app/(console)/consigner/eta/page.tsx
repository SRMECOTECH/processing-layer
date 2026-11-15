"use client";

import { PortalEta } from "@/components/routes/portal-eta";

// The consigner's shipments on their planned roads: live ETA to each consignee against the promise.
export default function Page() {
  return (
    <PortalEta description="Every truck carrying your goods, on its planned road: when it will reach your customer against the ETA promised at dispatch, and whether it left the route on the way." />
  );
}
