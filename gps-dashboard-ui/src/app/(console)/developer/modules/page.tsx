"use client";

import Link from "next/link";
import { Boxes } from "lucide-react";
import { Card, Code, FeedTag, PageHeader } from "@/components/ops/kit";
import { BUSINESS_RULES, PROCESSORS, SINKS, type PipelineModule } from "@/lib/modules";

const GROUPS: [string, PipelineModule[]][] = [
  ["Processors", PROCESSORS],
  ["Business logic", BUSINESS_RULES],
  ["Ingestion and stores", SINKS],
];

export default function ModuleRegistryPage() {
  return (
    <div className="stack">
      <PageHeader
        title="Module registry"
        description="Every module after the Kafka output topic, as defined in src/lib/modules.ts. The pipeline diagram, module headers and “how it works” cards read from this list, so a new module starts here."
      />

      {GROUPS.map(([title, modules]) => (
        <Card key={title} icon={Boxes} title={title} subtitle={`${modules.length} modules`} flush>
          <div className="data-table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Module</th>
                  <th>Status</th>
                  <th>Consumer group</th>
                  <th>State</th>
                  <th>Configuration</th>
                  <th>Emits</th>
                </tr>
              </thead>
              <tbody>
                {modules.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <Link className="link" href={m.href}>
                        {m.name}
                      </Link>
                    </td>
                    <td>
                      <FeedTag feed={m.status} />
                    </td>
                    <td>
                      <span className="meta-codes">
                        {m.consumerGroups.map((g) => (
                          <Code key={g}>{g}</Code>
                        ))}
                      </span>
                    </td>
                    <td>
                      <span className="meta-codes">
                        {m.state.map((s) => (
                          <Code key={s}>{s}</Code>
                        ))}
                      </span>
                    </td>
                    <td className="muted wrap">{m.config}</td>
                    <td className="muted wrap">{m.emits.join(", ") || "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      ))}
    </div>
  );
}
