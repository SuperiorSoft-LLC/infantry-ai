// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one daemon store of this window, and where it connects.
//   Tauri:   the `endpoint` command (INFANTRY_URL / INFANTRY_PORT / token file, like the CLI), socket from Rust.
//   Browser: `?daemon=ws://…`, else VITE_INFANTRY_URL, else same-origin `/ws` (the daemon, or the dev proxy).
//   Stress:  INFANTRY_UI_STRESS=1 (Tauri) or `?stress=1` (browser): fake sessions, no daemon.
import { useStore } from "zustand";
import { browserTransport, createDaemonStore, startStress, type DaemonActions, type DaemonState, type DaemonStore, type StressOptions } from "@infantry/client";
import { tauriTransport } from "./tauri-transport";

interface Endpoint {
  url: string;
  token: string | null;
  stress: boolean;
}

export const isTauri = typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

let store: DaemonStore | null = null;
export let stressMode = false;

async function resolveEndpoint(): Promise<Endpoint> {
  const q = new URLSearchParams(location.search);
  if (isTauri) {
    const { invoke } = await import("@tauri-apps/api/core");
    const e = await invoke<Endpoint>("endpoint");
    return { ...e, stress: e.stress || q.get("stress") === "1" };
  }
  const sameOrigin = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`;
  const url = q.get("daemon") ?? (import.meta.env.VITE_INFANTRY_URL as string | undefined) ?? sameOrigin;
  return { url, token: null, stress: q.get("stress") === "1" };
}

/** Stress knobs from the query string (`?sessions=6&messages=10000&fps=100`), each falling back to the default. */
function stressOptions(): Partial<StressOptions> {
  const q = new URLSearchParams(location.search);
  const n = (k: string) => (q.get(k) != null && Number.isFinite(Number(q.get(k))) ? Number(q.get(k)) : undefined);
  return { sessions: n("sessions"), messages: n("messages"), framesPerSecond: n("fps") };
}

export async function initDaemon(): Promise<DaemonStore> {
  if (store) return store;
  const e = await resolveEndpoint();
  stressMode = e.stress;
  const transport = isTauri ? tauriTransport(e.url, e.token) : browserTransport(e.url, e.token);
  store = createDaemonStore({ url: e.url, transport, offline: e.stress });
  if (e.stress) {
    const opts = Object.fromEntries(Object.entries(stressOptions()).filter(([, v]) => v !== undefined));
    startStress(store, opts);
  } else store.getState().connect();
  return store;
}

export function daemonStore(): DaemonStore {
  if (!store) throw new Error("initDaemon() must run before the app renders");
  return store;
}

/** Read the daemon store from a component; re-renders only when the selected slice changes. */
export function useDaemon<T>(select: (s: DaemonState & DaemonActions) => T): T {
  return useStore(daemonStore(), select);
}
