"use client";

import { ArrowRight, Building2, Globe, Truck } from "@/components/icons";
import { Card } from "@/components/ops/kit";
import { ModuleHeader } from "@/components/ops/module";
import { RuleEditor, scopeKind } from "@/components/ops/speed-rules";
import { moduleById } from "@/lib/modules";
import type { SpeedRule } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const SPEED = moduleById("speed")!;

export default function SpeedRulesPage() {
  const rules = usePoll<SpeedRule[]>("/api/speed/rules", 10000);
  const byKind = (kind: string) => (rules.data ?? []).filter((r) => scopeKind(r.scope) === kind).length;

  return (
    <div className="stack">
      <ModuleHeader
        module={SPEED}
        title="Entity rules"
        description="Every entity can have its own business logic: a limit, how long it must be held, how many pings, and how strictly the speedometer must agree with GPS. Rules live in Redis, so they change without a restart."
      />

      <Card title="How a reading finds its rule" subtitle="The most specific rule wins, like Traccar's device, group and server attributes.">
        <div className="resolve">
          <div className="resolve-step">
            <Truck size={18} />
            <div>
              <div className="resolve-name">Vehicle</div>
              <div className="mono faint">vehicle:{"{registration}"}</div>
            </div>
            <span className="resolve-count">{byKind("vehicle")}</span>
          </div>
          <ArrowRight size={16} className="resolve-arrow" />
          <div className="resolve-step">
            <Building2 size={18} />
            <div>
              <div className="resolve-name">Company</div>
              <div className="mono faint">tenant:{"{code}"}</div>
            </div>
            <span className="resolve-count">{byKind("tenant")}</span>
          </div>
          <ArrowRight size={16} className="resolve-arrow" />
          <div className="resolve-step">
            <Globe size={18} />
            <div>
              <div className="resolve-name">Default</div>
              <div className="mono faint">default</div>
            </div>
            <span className="resolve-count">{byKind("default")}</span>
          </div>
        </div>
      </Card>

      <RuleEditor rules={rules.data} onSaved={rules.refresh} />
    </div>
  );
}
