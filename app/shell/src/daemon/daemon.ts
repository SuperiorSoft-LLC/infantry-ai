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
import { createDaemonStore, decidePhoneStart, enableHops, hop, startStress, type ConnectWhy, type RemoteCheck, type DaemonActions, type DaemonState, type DaemonStore, type HostEndpoint, type StressOptions } from "@infantry/client";
import { host } from "@/host";

let store: DaemonStore | null = null;
/** The daemon this window connects to: its socket URL and token (null until initDaemon; the embedded runner has none). */
let link: { url: string; token: string | null } | null = null;
export let stressMode = false;
/** True when the sessions run in this app's own embedded runner (no daemon). */
export let localMode = false;
/** The perf harness's scenario, when one was given. */
export let perfScenario: string | null = null;
/** Hop timings with INFANTRY_UI_HOPS=board: the window opens the board by itself (no synthetic input), so a run needs no click. */
export let hopsOpenBoard = false;
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
/** PHONE-1: why a phone opened on the connect screen (null = it opened the app). `remote` is the saved PC, kept until Forget. */
export let phoneStart: { why: ConnectWhy; detail: string | null } | null = null;
/** PHONE-1: the in-app "Scan QR" asks for the connect screen once, without forgetting the saved PC. */
/** A saved PC has this long (the Rust check has 5 s) before the phone opens the connect screen, whatever the check does. */
const PHONE_CHECK_MS = 7000;
export const ASK_CONNECT_KEY = "infantry.phone.connect";

async function resolveEndpoint(): Promise<HostEndpoint> {
  const q = new URLSearchParams(location.search);
  // A phone whose app command fails still opens the connect screen, never the desktop path.
  const e = await host.endpoint().catch((err) => {
    if (host.kind !== "phone") throw err;
    return { url: "", token: null, stress: false, mode: "remote", error: err instanceof Error ? err.message : String(err) } as HostEndpoint;
  });
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
  const hopsQuery = new URLSearchParams(location.search).get("hops");
  hopsOpenBoard = e.hops === "board" || hopsQuery === "board";
  if (e.hops || hopsQuery) {
    // One JSON line per hop: the console, and (in the app) <state>/hop-timings.log through the app's own command.
    enableHops((line) => {
      console.debug(`[hop] ${line}`);
      if (host.kind !== "browser") void host.call("hops_report", { line }).catch(() => {});
    });
    hop("page.start");
  }
  // A phone never runs a daemon: a missing mode on it is `remote`, not the desktop's `connect`.
  runnerMode = e.mode ?? (host.kind === "phone" ? "remote" : host.kind !== "browser" ? (localMode ? "embedded" : "connect") : "browser");
  daemonError = e.error ?? null;
  daemonNote = e.note ?? null;
  if (daemonNote) console.warn(`[infantry] ${daemonNote}`);
  if (daemonError) console.error(`[infantry] ${runnerMode} mode: ${daemonError}`);
  isMobile = runnerMode === "remote" || host.kind === "phone";
  if (isMobile) {
    // PHONE-1: a saved PC that answers opens the app; anything else opens the connect screen (the pairing stays saved).
    const asked = (() => { try { return sessionStorage.getItem(ASK_CONNECT_KEY) === "1"; } catch { return false; } })();
    const canCheck = !!e.url && !e.error;
    const check = canCheck && !asked ? await Promise.race([host.call<RemoteCheck>("remote_check").catch(() => null), new Promise<null>((r) => setTimeout(() => r(null), PHONE_CHECK_MS))]) : null;
    const d = decidePhoneStart({ mode: e.mode, url: e.url, tokenError: e.error, check });
    if (asked && e.url) phoneStart = { why: "asked", detail: null };
    else if (d.open === "connect") phoneStart = { why: d.why, detail: e.error ?? null };
  }
  needsPairing = isMobile && phoneStart !== null;
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
