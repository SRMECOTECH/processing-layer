"use client";

import { useEffect, useState } from "react";
import type { DeviceLoadView } from "./types";

export type LiveVehicle = {
  imei: string;
  partition: number;
  registrationNo: string;
  tenantCode: string;
  latitude: number;
  longitude: number;
  speedKmph: number;
  moving: boolean;
  recordedAt: string;
};

/**
 * Latest Redis position of up to `limit` devices (optionally only IMEIs containing `filter`).
 * The device list is fetched first, then each device's live position, every `intervalMs`.
 */
export function useLiveVehicles(limit: number, filter = "", intervalMs = 3000) {
  const [vehicles, setVehicles] = useState<LiveVehicle[]>();
  const [total, setTotal] = useState<number>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;

    async function tick() {
      if (document.visibilityState === "hidden") {
        timer = setTimeout(tick, intervalMs);
        return;
      }
      try {
        const query = filter.trim() ? `?imei=${encodeURIComponent(filter.trim())}` : "";
        const list = await getJson<DeviceLoadView[]>(`/api/engine/devices${query}`);
        const details = await Promise.all(
          list.slice(0, limit).map((d) => getJson<DeviceLoadView>(`/api/engine/devices/${encodeURIComponent(d.imei)}`)),
        );
        if (cancelled) return;
        setTotal(list.length);
        setVehicles(details.filter((d) => d.livePosition && d.livePosition.imei).map(toVehicle));
        setError(undefined);
      } catch (problem) {
        if (!cancelled) setError(problem instanceof Error ? problem.message : "Request failed");
      } finally {
        if (!cancelled) timer = setTimeout(tick, intervalMs);
      }
    }

    tick();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [limit, filter, intervalMs]);

  return { vehicles, total, error };
}

function toVehicle(d: DeviceLoadView): LiveVehicle {
  const p = d.livePosition;
  return {
    imei: d.imei,
    partition: d.partition,
    registrationNo: p.vehicleRegistrationNo || "",
    tenantCode: p.tenantCode || "",
    latitude: Number(p.latitude),
    longitude: Number(p.longitude),
    speedKmph: Number(p.speedKmph),
    moving: p.movementStatus === "MOVING",
    recordedAt: p.recordedAt,
  };
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { cache: "no-store" });
  if (!response.ok) throw new Error(`The service answered ${response.status}`);
  return (await response.json()) as T;
}
