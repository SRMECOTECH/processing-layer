"use client";

import { Bot, CloudSun, TrafficCone } from "lucide-react";
import { Card, KeyValues, Progress, Stats, Tile } from "@/components/ops/kit";
import { ModuleHeader, SettingsCard } from "@/components/ops/module";
import { formatNumber, formatPercent } from "@/lib/format";
import { moduleById } from "@/lib/modules";
import type { VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const STOPPAGE = moduleById("stoppage")!;

const VERDICTS: [string, string, "good" | "bad" | "warn" | undefined][] = [
  ["JUSTIFIED_TRAFFIC", "Explained by traffic", "good"],
  ["JUSTIFIED_WEATHER", "Explained by weather", "good"],
  ["JUSTIFIED_TRAFFIC_AND_WEATHER", "Traffic and weather", "good"],
  ["UNEXPLAINED", "Unexplained (raised to HIGH)", "bad"],
  ["PENDING", "Still being checked", undefined],
];

export default function AgentsPage() {
  const stoppages = usePoll<VehicleEventView[]>("/api/events?type=STOPPAGE&limit=300", 5000);
  const verdicts = new Map<string, number>();
  let weather: Record<string, unknown> | undefined;
  let traffic: Record<string, unknown> | undefined;
  for (const e of stoppages.data ?? []) {
    const verdict = String(e.details.contextVerdict ?? "PENDING");
    verdicts.set(verdict, (verdicts.get(verdict) ?? 0) + 1);
    if (!weather && e.details.weather) weather = e.details.weather as Record<string, unknown>;
    if (!traffic && e.details.traffic) traffic = e.details.traffic as Record<string, unknown>;
  }
  const total = stoppages.data?.length ?? 0;
  const justified = [...verdicts.entries()].filter(([v]) => v.startsWith("JUSTIFIED")).reduce((a, [, n]) => a + n, 0);
  const unexplained = verdicts.get("UNEXPLAINED") ?? 0;

  return (
    <div className="stack">
      <ModuleHeader
        module={STOPPAGE}
        title="Traffic & weather agents"
        description="When a vehicle stops with the engine on, two agents look for a reason before anyone is alerted. A stop with no explanation is raised to HIGH."
      />

      <Stats columns={3}>
        <Tile label="Stoppages checked" value={stoppages.data ? total : undefined} big />
        <Tile label="Explained" value={stoppages.data ? (total ? (justified / total) * 100 : 0) : undefined} format={(v) => v.toFixed(1)} unit="%" tone="good" big foot={`${formatNumber(justified)} by traffic or weather`} />
        <Tile label="Unexplained" value={stoppages.data ? unexplained : undefined} tone={unexplained ? "bad" : undefined} big foot="raised to HIGH" />
      </Stats>

      <Card icon={Bot} title="Verdicts" subtitle="Latest 300 stoppages." live>
        <div className="bars">
          {VERDICTS.map(([key, label, tone]) => {
            const n = verdicts.get(key) ?? 0;
            return (
              <div className="bar-row" key={key}>
                <div className="bar-label">
                  <span className={tone === "bad" ? "bad" : undefined}>{label}</span>
                  <span className="mono">
                    {formatNumber(n)} <span className="faint">{total ? formatPercent(n / total) : ""}</span>
                  </span>
                </div>
                <Progress value={total ? n / total : 0} tone={tone} />
              </div>
            );
          })}
        </div>
      </Card>

      <div className="grid-2">
        <Card icon={TrafficCone} title="Traffic agent" subtitle="Fleet vehicles with the engine on within 1 km, plus TomTom when a key is set." live>
          {traffic ? (
            <KeyValues rows={Object.entries(traffic).map(([k, v]) => [k, typeof v === "object" ? JSON.stringify(v) : String(v)])} />
          ) : (
            <KeyValues
              rows={[
                ["Source", "Own fleet (vehicles standing nearby with the engine on)"],
                ["Radius", "1 km"],
                ["External", "TomTom flow API when TOMTOM_API_KEY is set"],
                ["Last result", <span className="faint" key="n">No stoppage checked yet</span>],
              ]}
            />
          )}
        </Card>
        <Card icon={CloudSun} title="Weather agent" subtitle="Open-Meteo, current weather for the 0.1° cell of the stop (cached 10 min)." live>
          {weather ? (
            <KeyValues
              rows={[
                ["Area", <span className="mono" key="c">{String(weather.cell ?? "–")}</span>],
                ["Weather", String(weather.description ?? "–")],
                ["Temperature", `${weather.temperatureC ?? "–"} °C`],
                ["Precipitation", `${weather.precipitationMm ?? 0} mm`],
                ["Wind", `${weather.windKmph ?? 0} km/h`],
                ["Severe enough to stop", weather.severe ? <span className="bad" key="s">yes</span> : "no"],
              ]}
            />
          ) : (
            <KeyValues
              rows={[
                ["Source", "api.open-meteo.com (no key needed)"],
                ["Cache", "gps:context:weather:{cell}, 10 minutes"],
                ["Last result", <span className="faint" key="n">No stoppage checked yet</span>],
              ]}
            />
          )}
        </Card>
      </div>

      <SettingsCard
        title="Agent settings"
        source="application.yml · gps.stream.enrichment"
        settings={[
          { label: "Weather agent", value: "on" },
          { label: "Traffic radius", value: 1000, unit: "m" },
          { label: "Timeout per call", value: 3000, unit: "ms" },
          { label: "TomTom key", value: "from env", hint: "TOMTOM_API_KEY" },
        ]}
      />
    </div>
  );
}
