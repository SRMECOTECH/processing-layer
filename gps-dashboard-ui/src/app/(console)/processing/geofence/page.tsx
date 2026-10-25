"use client";

import { useState } from "react";
import { ChevronRight, Grid3x3, Layers } from "lucide-react";
import { Card, DemoButton, Tag } from "@/components/ops/kit";
import { HowItWorks, ModuleHeader, PlannedNote, SettingsCard } from "@/components/ops/module";
import { ZONE_TREE, type Zone } from "@/lib/examples";
import { moduleById } from "@/lib/modules";

const GEOFENCE = moduleById("geofence")!;

export default function ZoneHierarchyPage() {
  return (
    <div className="stack">
      <ModuleHeader module={GEOFENCE} title="Nested geofencing · zone hierarchy" actions={<DemoButton primary>New zone</DemoButton>} />
      <PlannedNote what="Nested geofencing" />

      <div className="grid-main-side">
        <Card icon={Layers} title="Zone tree" subtitle="Each zone sits inside its parent. Counts are vehicles inside right now." live={false}>
          <div className="tree">
            {ZONE_TREE.map((z) => (
              <ZoneRow key={z.id} zone={z} depth={0} />
            ))}
          </div>
        </Card>

        <Card icon={Grid3x3} title="Nesting, drawn" subtitle="Region › site › yard › dock" live={false}>
          <svg className="geo" viewBox="0 0 400 300" role="img" aria-label="Nested zones with vehicles inside">
            <rect className="zone l1" x="10" y="10" width="380" height="280" rx="18" />
            <rect className="zone l2" x="50" y="50" width="250" height="200" rx="14" />
            <rect className="zone l3" x="110" y="95" width="150" height="120" rx="10" />
            <rect className="zone l4" x="165" y="130" width="70" height="55" rx="8" />
            <text x="24" y="32">Region</text>
            <text x="62" y="72">Site</text>
            <text x="122" y="116">Yard</text>
            <text x="172" y="150">Dock</text>
            <circle className="vehicle" cx="200" cy="168" r="6" />
            <circle className="vehicle" cx="140" cy="190" r="6" />
            <circle className="vehicle" cx="330" cy="120" r="6" />
            <circle className="vehicle out" cx="370" cy="275" r="6" />
          </svg>
        </Card>
      </div>

      <SettingsCard
        title="Geofence settings"
        subtitle="Proposed defaults."
        source="planned"
        settings={[
          { label: "Maximum nesting depth", value: 4, unit: "levels" },
          { label: "Boundary tolerance", value: 15, unit: "m", hint: "No enter/exit flapping on the edge" },
          { label: "Confirm entry after", value: 2, unit: "readings" },
          { label: "Dwell alert after", value: 30, unit: "min", hint: "Per zone, overridable" },
          { label: "Shapes", value: "polygon, circle" },
          { label: "Index", value: "R-tree / PostGIS GiST" },
        ]}
      />

      <HowItWorks module={GEOFENCE} />
    </div>
  );
}

function ZoneRow({ zone, depth }: { zone: Zone; depth: number }) {
  const [open, setOpen] = useState(depth < 2);
  const hasChildren = !!zone.children?.length;
  return (
    <div className="tree-node">
      <div className="tree-row" style={{ paddingLeft: depth * 22 + 8 }}>
        <button
          type="button"
          className={`tree-toggle${open ? " open" : ""}`}
          onClick={() => setOpen(!open)}
          disabled={!hasChildren}
          aria-label={open ? "Collapse" : "Expand"}
        >
          {hasChildren && <ChevronRight size={14} />}
        </button>
        <span className="tree-name">{zone.name}</span>
        <Tag tone="neutral">{zone.level.toUpperCase()}</Tag>
        <span className="tree-meta mono">{zone.area}</span>
        <span className="tree-meta mono">{zone.inside} inside</span>
        <span className="tree-meta faint">dwell {zone.dwell}</span>
      </div>
      {open && zone.children?.map((c) => <ZoneRow key={c.id} zone={c} depth={depth + 1} />)}
    </div>
  );
}
