"use client";

import { Suspense } from "react";
import { Truck } from "@/components/icons";
import { Card } from "@/components/ops/kit";
import { DemoShipmentsButton } from "@/components/tms/admin";
import { ImportPanel } from "@/components/tms/import";

// The GPS provider uploads shipment files for any transporter, or loads the demo shipments from smart_truck.
export default function Page() {
  return (
    <Suspense fallback={null}>
      <div className="stack">
        <ImportPanel />
        <Card
          icon={Truck}
          title="Demo: the trips the simulator drives"
          subtitle="Their smart_truck TMS rows (tta_trips), moved to the replay clock and imported like a file - so the portals show real trips moving live"
        >
          <DemoShipmentsButton />
        </Card>
      </div>
    </Suspense>
  );
}
