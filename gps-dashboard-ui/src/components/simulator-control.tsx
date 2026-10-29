"use client";

import { useState } from "react";
import { Play, Square } from "@/components/icons";
import { useOverview } from "@/lib/overview-context";
import type { SimulationStatus } from "@/lib/types";
import { post } from "@/lib/use-poll";

/** Starts or stops the simulated fleet (POST /api/simulator/start | stop). */
export function FleetButton() {
  const { data, refresh } = useOverview();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string>();
  const running = data?.simulator.running ?? false;

  async function toggle() {
    setBusy(true);
    setProblem(undefined);
    try {
      await post<SimulationStatus>(running ? "/api/simulator/stop" : "/api/simulator/start");
      refresh();
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "The simulator did not answer");
    } finally {
      setBusy(false);
    }
  }

  return (
    <button
      type="button"
      className={`btn ${running ? "soft" : "primary"}`}
      onClick={toggle}
      disabled={busy || !data}
      title={problem ?? data?.simulator.lastMessage ?? undefined}
    >
      {running ? <Square size={14} /> : <Play size={14} />}
      {busy ? "Working" : problem ? "Try again" : running ? "Stop fleet" : "Start fleet"}
    </button>
  );
}
