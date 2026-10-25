"use client";

import { CloudSun, OctagonAlert, Power, TrafficCone } from "lucide-react";
import { EventTable } from "@/components/ops/events";
import { Card, KeyValues, PageTitle, Segmented, Tile } from "@/components/ops/kit";
import { formatNumber } from "@/lib/format";
import type { EventTypeCount, VehicleEventView } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import { useState } from "react";

type View = "STOPPAGE" | "PARKED" | "IDLING" | "IGNITION_ON,IGNITION_OFF";

export default function StoppagePage() {
  const [view, setView] = useState<View>("STOPPAGE");
  const summary = usePoll<EventTypeCount[]>("/api/events/summary", 5000);
  const stoppages = usePoll<VehicleEventView[]>("/api/events?type=STOPPAGE&limit=300", 5000);
  const listed = usePoll<VehicleEventView[]>(`/api/events?type=${view}&limit=40`, 4000);
  const count = (type: string) => summary.data?.find((s) => s.type === type);

  const verdicts = new Map<string, number>();
  let weatherChecked: Record<string, unknown> | undefined;
  for (const e of stoppages.data ?? []) {
    const verdict = String(e.details.contextVerdict ?? "PENDING");
    verdicts.set(verdict, (verdicts.get(verdict) ?? 0) + 1);
    if (!weatherChecked && e.details.weather) weatherChecked = e.details.weather as Record<string, unknown>;
  }
  const justified = [...verdicts.entries()].filter(([v]) => v.startsWith("JUSTIFIED")).reduce((a, [, n]) => a + n, 0);

  return (
    <div className="stack">
      <PageTitle
        title="Stoppage and ignition"
        subtitle="Stops are found from time and distance travelled (real-time speed, not just the speedometer). Engine on while standing is a stoppage alarm, checked by the traffic and weather agents; engine off is parking and is neutralised."
      />

      <div className="grid-4">
        <Tile label="Stoppages now (engine on)" value={count("STOPPAGE")?.openNow ?? 0} tone={(count("STOPPAGE")?.openNow ?? 0) > 0 ? "accent" : undefined} big foot={`${count("STOPPAGE")?.last24Hours ?? 0} in 24 hours`} />
        <Tile label="Parked now (neutralised)" value={count("PARKED")?.openNow ?? 0} big foot={`${count("PARKED")?.last24Hours ?? 0} in 24 hours, no alarm`} />
        <Tile label="Idling now" value={count("IDLING")?.openNow ?? 0} big foot="engine running, standing over 5 min" />
        <Tile label="Ignition changes (24 h)" value={(count("IGNITION_ON")?.last24Hours ?? 0) + (count("IGNITION_OFF")?.last24Hours ?? 0)} big foot="device wire or virtual" />
      </div>

      <div className="grid-2">
        <Card icon={TrafficCone} title="Why vehicles stopped" subtitle="Verdicts of the context agents on recent stoppages" live>
          <KeyValues
            rows={[
              ["Explained by traffic", formatNumber(verdicts.get("JUSTIFIED_TRAFFIC") ?? 0)],
              ["Explained by weather", formatNumber(verdicts.get("JUSTIFIED_WEATHER") ?? 0)],
              ["Traffic and weather", formatNumber(verdicts.get("JUSTIFIED_TRAFFIC_AND_WEATHER") ?? 0)],
              [<span className="bad" key="u">Unexplained (raised to HIGH)</span>, <span className="bad" key="n">{formatNumber(verdicts.get("UNEXPLAINED") ?? 0)}</span>],
              ["Still being checked", formatNumber(verdicts.get("PENDING") ?? 0)],
            ]}
          />
          <div className="card-foot">
            <span>
              {formatNumber(justified)} of {formatNumber(stoppages.data?.length ?? 0)} explained
            </span>
            <span>Traffic: fleet vehicles with engine on within 1 km</span>
          </div>
        </Card>
        <Card icon={CloudSun} title="Weather agent" subtitle="Open-Meteo, current weather for the 0.1 degree cell of the stop (cached 10 min)" live>
          {weatherChecked ? (
            <KeyValues
              rows={[
                ["Area", <span className="mono" key="c">{String(weatherChecked.cell ?? "–")}</span>],
                ["Weather", String(weatherChecked.description ?? "–")],
                ["Temperature", `${weatherChecked.temperatureC ?? "–"} °C`],
                ["Precipitation", `${weatherChecked.precipitationMm ?? 0} mm`],
                ["Wind", `${weatherChecked.windKmph ?? 0} km/h`],
                ["Severe enough to stop", weatherChecked.severe ? <span className="bad" key="s">yes</span> : "no"],
              ]}
            />
          ) : (
            <p className="muted">No stoppage has been checked yet.</p>
          )}
        </Card>
      </div>

      <Card
        icon={view === "STOPPAGE" ? OctagonAlert : Power}
        title="Events"
        subtitle="From consumer groups gps-stoppage-service and gps-ignition-service"
        live
        actions={
          <Segmented<View>
            label="Event type"
            value={view}
            onChange={setView}
            options={[
              ["STOPPAGE", "Stoppages"],
              ["PARKED", "Parked"],
              ["IDLING", "Idling"],
              ["IGNITION_ON,IGNITION_OFF", "Ignition"],
            ]}
          />
        }
      >
        <EventTable events={listed.data} showType={view === "IGNITION_ON,IGNITION_OFF"} />
      </Card>
    </div>
  );
}
