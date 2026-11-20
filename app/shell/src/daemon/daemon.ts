// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one daemon store of this window, and where it connects (the host decides, HC-1 @/host).
//   Tauri:   the `endpoint` command: `daemon` mode (default) = the infantry-daemon sidecar the app started or found;
//            `connect` = INFANTRY_URL / INFANTRY_PORT (+ token file, like the CLI); `embedded` = the in-app runner
//            (INFANTRY_UI_LOCAL=1 or the Settings > Daemon switch). The socket is opened from Rust.
//   Browser: `?daemon=ws://…`, else VITE_INFANTRY_URL, else same-origin `/ws` (the daemon, or the dev proxy).
//   Phone:   `remote` (RUST-MOBILE-1): no daemon runs on the phone; the paired remote daemon's URL, its token from the
//            keystore. Unpaired (no URL yet), the app shows the connect screen instead of the shell.
//   Stress:  INFANTRY_UI_STRESS=1 (Tauri) or `?stress=1` (browser): fake sessions, no daemon.
import { useStore } from "zustand";
import { createDaemonStore, startStress, type DaemonActions, type DaemonState, type DaemonStore, type HostEndpoint, type StressOptions } from "@infantry/client";
import { host } from "@/host";

let store: DaemonStore | null = null;
/** The daemon this window connects to: its socket URL and token (null until initDaemon; the embedded runner has none). */
let link: { url: string; token: string | null } | null = null;
export let stressMode = false;
/** True when the sessions run in this app's own embedded runner (no daemon). */
export let localMode = false;
/** The perf harness's scenario, when one was given. */
export let perfScenario: string | null = null;
/** `embedded` | `daemon` | `connect` (desktop app), `remote` (phone app), `browser` otherwise. */
export let runnerMode = "browser";
/** Daemon mode: why the sidecar did not start (null = it is up). */
export let daemonError: string | null = null;
/** Daemon mode: a plain warning that the running daemon is stale (it may refuse newer frames). */
export let daemonNote: string | null = null;
/** A phone build (Tauri mobile): it connects to a remote daemon and runs none. */
export let isMobile = false;
/** A phone that is not paired with a daemon yet: main.tsx shows the connect screen. */
export let needsPairing = false;
/** The paired daemon: its socket URL and display name (remote mode). */
export let remote: { url: string; name: string | null } | null = null;

async function resolveEndpoint(): Promise<HostEndpoint> {
  const q = new URLSearchParams(location.search);
  const e = await host.endpoint();
  if (e) return { ...e, stress: e.stress || q.get("stress") === "1" };
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
  localMode = host.embedded !== null && e.local === true && !e.stress;
  perfScenario = e.perf ?? null;
  runnerMode = e.mode ?? (host.kind !== "browser" ? (localMode ? "embedded" : "connect") : "browser");
  daemonError = e.error ?? null;
  daemonNote = e.note ?? null;
  if (daemonNote) console.warn(`[infantry] ${daemonNote}`);
  if (daemonError) console.error(`[infantry] ${runnerMode} mode: ${daemonError}`);
  isMobile = runnerMode === "remote";
  needsPairing = isMobile && !e.url;
  remote = isMobile && e.url ? { url: e.url, name: e.name ?? null } : null;
  link = localMode || e.stress || needsPairing ? null : { url: e.url, token: e.token };
  const transport = localMode && host.embedded ? host.embedded.transport() : host.transport(e.url, e.token);
  store = createDaemonStore({ url: localMode ? "embedded runner" : runnerMode === "daemon" ? `${e.url} (sidecar)` : e.url || "not paired", transport, offline: e.stress || needsPairing });
  if (needsPairing) return store;
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

/** The daemon this window is connected to (its HTTP routes: `/ui-plugins`); null for the embedded runner, stress or an unpaired phone. */
export function daemonLink(): { url: string; token: string | null } | null {
  return link;
}

/** Read the daemon store from a component; re-renders only when the selected slice changes. */
export function useDaemon<T>(select: (s: DaemonState & DaemonActions) => T): T {
  return useStore(daemonStore(), select);
}
