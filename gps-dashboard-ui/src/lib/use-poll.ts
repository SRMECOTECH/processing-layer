"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useLiveUpdatesPaused } from "./preferences";

export type Poll<T> = {
  data: T | undefined;
  /** Set when the last request failed; data keeps the last good answer. */
  error: string | undefined;
  loading: boolean;
  refresh: () => void;
};

/**
 * Fetches a JSON endpoint now and then every `intervalMs`, keeping the last good answer on screen
 * while the next one loads. Pauses while the browser tab is hidden or the viewer paused live updates.
 * Pass null to skip fetching.
 */
export function usePoll<T>(path: string | null, intervalMs = 2000): Poll<T> {
  const paused = useLiveUpdatesPaused();
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(path !== null);
  const runNow = useRef<() => void>(() => {});

  useEffect(() => {
    if (path === null || paused) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;

    const schedule = () => {
      clearTimeout(timer);
      timer = setTimeout(tick, intervalMs);
    };

    async function tick() {
      if (document.visibilityState === "hidden") {
        schedule();
        return;
      }
      controller?.abort();
      controller = new AbortController();
      try {
        const response = await fetch(path!, { cache: "no-store", signal: controller.signal });
        if (response.status === 401) {
          backToSignIn();
          return;
        }
        if (!response.ok) {
          throw new Error(await problemText(response));
        }
        const json = (await response.json()) as T;
        if (!cancelled) {
          setData(json);
          setError(undefined);
        }
      } catch (problem) {
        if (cancelled || (problem instanceof DOMException && problem.name === "AbortError")) {
          return;
        }
        setError(problem instanceof Error ? problem.message : "Request failed");
      } finally {
        if (!cancelled) {
          setLoading(false);
          schedule();
        }
      }
    }

    const onVisible = () => {
      if (document.visibilityState === "visible") {
        tick();
      }
    };

    runNow.current = () => tick();
    setLoading(true);
    tick();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [path, intervalMs, paused]);

  const refresh = useCallback(() => runNow.current(), []);
  return { data, error, loading, refresh };
}

/** Sends a POST to the service and returns the JSON answer. */
export async function post<T>(path: string): Promise<T> {
  const response = await fetch(path, { method: "POST", cache: "no-store" });
  if (response.status === 401) backToSignIn();
  if (!response.ok) {
    throw new Error(await problemText(response));
  }
  return (await response.json()) as T;
}

/** The session ended (signed out in another tab, or expired): sign in again and come back to this page. */
function backToSignIn() {
  window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`);
}

async function problemText(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: string; message?: string };
    return body.error ?? body.message ?? `The service answered ${response.status}`;
  } catch {
    return `The service answered ${response.status}`;
  }
}
