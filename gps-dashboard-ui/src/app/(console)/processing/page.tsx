"use client";

import { Boxes, Workflow } from "lucide-react";
import { Callout, Card, Code, PageHeader } from "@/components/ops/kit";
import { ModuleCard, PipelineDiagram } from "@/components/ops/module";
import { formatNumber } from "@/lib/format";
import { EVENTS_TOPIC, OUTPUT_TOPIC, PROCESSORS } from "@/lib/modules";
import { useOverview } from "@/lib/overview-context";

export default function PipelineOverviewPage() {
  const { data: o } = useOverview();
  const built = PROCESSORS.filter((m) => m.status !== "planned").length;
  const lag = o?.consumers.reduce((sum, c) => sum + c.totalLag, 0);

  return (
    <div className="stack">
      <PageHeader
        title="Pipeline overview"
        description="Everything that happens after the Kafka output topic. Each processor is its own consumer group, so all of them read every reading in parallel; their results feed the business logic, then ingestion control writes to PostgreSQL and ClickHouse."
        meta={[
          ["Normalized by", normalizedBy(o?.normalization?.engine)],
          ["Output topic", <Code key="t">{OUTPUT_TOPIC}</Code>],
          ["Partitions", "3 · keyed by IMEI"],
          ["Processors", `${built} built · ${PROCESSORS.length - built} planned`],
          ["Events topic", <Code key="e">{EVENTS_TOPIC}</Code>],
          ["Total lag", `${formatNumber(lag)} messages`],
        ]}
      />

      <Card icon={Workflow} title="Data flow after Kafka" subtitle="Live rates on every built stage. Dashed boxes are planned. Click any box to open it." live>
        <PipelineDiagram />
      </Card>

      <div>
        <div className="section-label">
          <Boxes size={15} /> Processors
        </div>
        <div className="module-grid">
          {PROCESSORS.map((m) => (
            <ModuleCard key={m.id} module={m} />
          ))}
        </div>
      </div>

      <Callout title="Why they run in parallel">
        A consumer group gets every message of the topic and keeps its own offset, so a slow processor never holds the others back. Inside a
        group Kafka gives each partition to one consumer, so a processor scales to 3 consumers. A vehicle's readings always land on the same
        partition, which keeps each vehicle's state in order.
      </Callout>
    </div>
  );
}

function normalizedBy(engine: string | undefined): string {
  if (engine === "kafka-streams") return "Kafka Streams · exactly-once";
  if (engine === "listener") return "Listener · at-least-once";
  if (engine === "direct") return "Direct flow";
  return "–";
}
