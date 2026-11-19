// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The health store (RUST-UI-5): `health` {} -> `health` (the daemon's HealthReport: daemon line, plugins, MCP bridges,
// hooks, free memory and disk, environment, the last restart events). `healthSummary` is the pure verdict the view shows.
import { createStore, type StoreApi } from "zustand/vanilla";
import { json, type Envelope } from "../wire";
import { requester, type FrameLink } from "./link";

export interface HealthDaemon {
  up?: boolean;
  pid?: number | null;
  port?: number | null;
  build?: string | null;
  startedAt?: number | null;
  uptimeMs?: number | null;
  lastRestartAt?: number | null;
  lastRestartCause?: string | null;
  source?: string;
}

export interface HealthEvent {
  at: number;
  kind: string;
  cause?: string | null;
  [k: string]: unknown;
}

export interface HealthReport {
  at: number;
  daemon: HealthDaemon;
  plugins: { id: string; status: string }[];
  mcp: Record<string, unknown>[];
  hooks?: unknown;
  hookFailures?: unknown;
  freeMemoryMb?: number | null;
  freeDiskMb?: number | null;
  environment?: Record<string, unknown>;
  events: HealthEvent[];
}

export interface HealthState {
  report: HealthReport | null;
  loading: boolean;
  refusal: string | null;
}

export interface HealthActions {
  refresh(): void;
  dispose(): void;
}

export type HealthStore = StoreApi<HealthState & HealthActions>;

export interface HealthSummary {
  tone: "green" | "amber" | "red";
  headline: string;
  faults: string[];
}

/** The verdict: red when the daemon is not up, amber when a plugin is not loaded, else green. */
export function healthSummary(r: HealthReport | null): HealthSummary {
  if (!r) return { tone: "red", headline: "No report yet", faults: [] };
  const faults = r.plugins.filter((p) => p.status !== "loaded").map((p) => `${p.id}: ${p.status}`);
  if (r.daemon.up === false) return { tone: "red", headline: "Daemon down", faults };
  const loaded = r.plugins.length - faults.length;
  return { tone: faults.length ? "amber" : "green", headline: `Daemon up · ${loaded} plugin(s) loaded${faults.length ? `, ${faults.length} not` : ""}`, faults };
}

/** `90061000` -> `1d`, `3720000` -> `1h 2m` (the CLI's `age`). */
export function age(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86_400) return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
  return `${Math.floor(s / 86_400)}d`;
}

export function parseHealth(data: string): HealthReport | null {
  const r = json<Record<string, unknown>>(data);
  if (!r || typeof r !== "object" || typeof r.daemon !== "object" || r.daemon === null) return null;
  return {
    ...(r as object),
    at: Number(r.at ?? Date.now()),
    daemon: r.daemon as HealthDaemon,
    plugins: Array.isArray(r.plugins) ? (r.plugins as { id: string; status: string }[]) : [],
    mcp: Array.isArray(r.mcp) ? (r.mcp as Record<string, unknown>[]) : [],
    events: Array.isArray(r.events) ? (r.events as HealthEvent[]) : [],
  } as HealthReport;
}

export function createHealthStore(link: FrameLink): HealthStore {
  return createStore<HealthState & HealthActions>()((set) => {
    const req = requester(link, "health", (why) => set({ refusal: why, loading: false }));
    const off = link.onFrame((env: Envelope) => {
      if (env.type === "health") {
        if (!req.settle(env)) return;
        const report = parseHealth(env.data);
        set(report ? { report, loading: false, refusal: null } : { loading: false, refusal: "health: the daemon's answer is not a health report" });
      } else if (env.type === "error" && (req.settle(env) || env.data.startsWith("health"))) {
        set({ refusal: env.data, loading: false });
      }
    });
    return {
      report: null,
      loading: false,
      refusal: null,
      refresh() {
        set({ loading: true });
        req.send("health", "{}");
      },
      dispose: off,
    };
  });
}
