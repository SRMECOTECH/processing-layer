"use client";

import { PageHeader } from "@/components/ops/kit";
import { SettingsCard } from "@/components/ops/module";

export default function ServiceSettingsPage() {
  return (
    <div className="stack">
      <PageHeader
        title="Service settings"
        description="The settings the GPS service reads from gps-application/src/main/resources/application.yml when it starts. Speed rules are the exception: they live in Redis and change without a restart."
      />

      <div className="grid-2">
        <SettingsCard
          title="Processing flow"
          source="gps.processing-flow"
          settings={[
            { label: "Flow", value: "kafka", hint: "direct: process and store in one call, no Kafka" },
            { label: "Partitions per topic", value: 3 },
          ]}
        />
        <SettingsCard
          title="Distance step"
          source="gps.distance"
          settings={[
            { label: "Jitter", value: 10, unit: "m" },
            { label: "Jitter max speed", value: 5, unit: "km/h" },
            { label: "Max implied speed", value: 200, unit: "km/h" },
            { label: "Min jump", value: 500, unit: "m" },
          ]}
        />
        <SettingsCard
          title="Stoppage"
          source="gps.stream.stoppage"
          settings={[
            { label: "Radius", value: 100, unit: "m" },
            { label: "Min duration", value: 180, unit: "s" },
            { label: "Stationary speed", value: 3, unit: "km/h" },
            { label: "Exit confirm", value: 2, unit: "readings" },
            { label: "Max gap", value: 1800, unit: "s" },
            { label: "Merge gap", value: 180, unit: "s" },
          ]}
        />
        <SettingsCard
          title="Ignition"
          source="gps.stream.ignition"
          settings={[
            { label: "Virtual off after", value: 300, unit: "s" },
            { label: "Idle speed", value: 3, unit: "km/h" },
            { label: "Idle alert", value: 300, unit: "s" },
            { label: "Virtual debounce", value: 2, unit: "readings" },
          ]}
        />
        <SettingsCard
          title="Vehicle state"
          source="gps.stream"
          settings={[
            { label: "History length", value: 1000, unit: "readings" },
            { label: "GPS σ", value: 12, unit: "m" },
            { label: "Acceleration σ", value: 1.5, unit: "m/s²" },
          ]}
        />
        <SettingsCard
          title="Enrichment agents"
          source="gps.stream.enrichment"
          settings={[
            { label: "Weather", value: "on" },
            { label: "Traffic radius", value: 1000, unit: "m" },
            { label: "Timeout", value: 3000, unit: "ms" },
            { label: "TomTom key", value: "TOMTOM_API_KEY" },
          ]}
        />
      </div>
    </div>
  );
}
