"use client";

import { Play, Settings2 } from "@/components/icons";
import { Card, KeyValues, PageHeader, Progress, Stats, Tag, Tile } from "@/components/ops/kit";
import { SettingsCard } from "@/components/ops/module";
import { FleetButton } from "@/components/simulator-control";
import { formatNumber, formatRate } from "@/lib/format";
import { useOverview } from "@/lib/overview-context";
import { ingress } from "@/lib/pipeline";

export default function SimulatorPage() {
  const { data: o } = useOverview();
  const sim = o?.simulator;
  const rate = ingress(o)?.messagesPerSecond;

  return (
    <div className="stack">
      <PageHeader
        title="Fleet simulator"
        description="Recorded truck trips from smart_truck replayed as live devices, so every processor sees realistic gaps between pings, stops, idling and over-speed."
        badges={sim ? <Tag tone={sim.running ? "good" : "neutral"}>{sim.running ? "RUNNING" : "STOPPED"}</Tag> : undefined}
        actions={<FleetButton />}
      />

      <Stats>
        <Tile label="Vehicles" value={sim?.vehicleCount} big />
        <Tile label="Frames sent" value={sim?.framesSent} big />
        <Tile label="Sending now" value={rate} format={formatRate} unit="frames/s" big />
        <Tile
          label="Drive progress"
          value={sim ? Math.round((sim.round / Math.max(1, sim.roundsPerDrive)) * 100) : undefined}
          unit="%"
          big
          foot={sim ? `round ${formatNumber(sim.round)} of ${formatNumber(sim.roundsPerDrive)}, drive ${sim.driveNumber}` : undefined}
        />
      </Stats>

      <Card icon={Play} title="Status" subtitle={sim?.lastMessage ?? "Connecting"} live>
        {sim && <Progress value={sim.round / Math.max(1, sim.roundsPerDrive)} />}
        <div style={{ marginTop: 18 }}>
          <KeyValues
            rows={[
              ["Processing flow", sim?.processingFlow ?? "–"],
              ["Replay as live", sim ? (sim.replayAsLive ? "yes: trips are re-stamped around now" : "no") : "–"],
              ["Drive", sim ? `${sim.driveNumber} (forwards, then back, and so on)` : "–"],
            ]}
          />
        </div>
      </Card>

      <SettingsCard
        title="Simulator settings"
        subtitle="Read when the service starts; change them in application.yml and restart."
        source="application.yml · gps.simulator"
        settings={[
          { label: "Vehicles", value: 150 },
          { label: "Pings per vehicle", value: 500 },
          { label: "Timing", value: "faithful", hint: "Real gaps between pings (about 1 minute)" },
          { label: "Catch-up", value: 120, unit: "min", hint: "Replay starts this far in the past" },
          { label: "Catch-up speed", value: "30×", hint: "Until the replay reaches now" },
          { label: "Devices with ignition", value: "50 %", hint: "The rest use virtual ignition" },
          { label: "Devices with odometer", value: "50 %", hint: "The rest use GPS distance" },
          { label: "Idling drivers", value: "25 %", hint: "Engine left running while standing" },
        ]}
        actions={<Settings2 size={16} className="faint" />}
      />
    </div>
  );
}
