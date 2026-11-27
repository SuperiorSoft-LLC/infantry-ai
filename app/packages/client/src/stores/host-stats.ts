// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// STATUS-LINE-1: the footer line "last frame 5s ago · RAM 10.6 GB free · agents CPU 0.1%". The daemon pushes `host_stats`
// every few seconds; "last frame" is the time since any frame came in on the link.
import { createStore, type StoreApi } from "zustand/vanilla";
import type { FrameLink } from "./link";

/** The `host_stats` body (null where the daemon's OS did not answer, or before its second tick). */
export interface HostStats {
  freeMb: number | null;
  totalMb: number | null;
  agents: number;
  agentsCpuPercent: number | null;
}

export interface HostStatsState {
  stats: HostStats | null;
  /** When the last frame of any type arrived, ms; null before the first. */
  lastFrameAt: number | null;
  dispose(): void;
}

export type HostStatsStore = StoreApi<HostStatsState>;

export function createHostStatsStore(link: FrameLink, now: () => number = Date.now): HostStatsStore {
  return createStore<HostStatsState>()((set) => {
    const off = link.onFrame((env) => {
      if (env.type !== "host_stats") return set({ lastFrameAt: now() });
      try {
        set({ stats: JSON.parse(env.data) as HostStats, lastFrameAt: now() });
      } catch {
        set({ lastFrameAt: now() });
      }
    });
    return { stats: null, lastFrameAt: null, dispose: off };
  });
}

/** "now" under a second, "5s", "3m", "2h". */
export function agoText(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 1) return "now";
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}

/** The line's parts; a part the daemon has not sent yet is left out. */
export function hostStatsParts(stats: HostStats | null, lastFrameAt: number | null, now: number): string[] {
  const out: string[] = [];
  if (lastFrameAt != null) out.push(`last frame ${agoText(now - lastFrameAt)}`);
  if (stats?.freeMb != null) out.push(`RAM ${(stats.freeMb / 1024).toFixed(1)} GB free`);
  if (stats?.agentsCpuPercent != null) out.push(`agents CPU ${stats.agentsCpuPercent.toFixed(1)}%`);
  return out;
}
