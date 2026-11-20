// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// HC-1 (AUDIT-1b): what the window runs in, behind one interface. The app never calls a platform API itself: the desktop
// and phone apps (Tauri) implement [Host] in app/shell/src/host/tauri/, the only folder allowed to import `@tauri-apps`
// (test/host-guard.test.ts fails on any other import); a plain browser gets [browserHost]. The shell, the web UI plugins
// (WEBUI-1) and the shell-only app all go through it.
import { browserTransport, type TransportFactory } from "./connection";

/** `desktop` (the installed app), `phone` (the mobile app, paired with a remote daemon) or `browser`. */
export type HostKind = "desktop" | "phone" | "browser";

/** Where the app says the daemon is (the Tauri `endpoint` command). A browser decides from the page instead. */
export interface HostEndpoint {
  url: string;
  token: string | null;
  stress: boolean;
  /** Agents run in this app's embedded runner (desktop only). */
  local?: boolean;
  /** The perf harness's scenario (INFANTRY_PERF_SCENARIO), run by perf-scenario.ts. */
  perf?: string | null;
  /** `embedded` | `daemon` | `connect` (desktop), `remote` (phone). */
  mode?: string;
  /** Daemon mode: why the sidecar did not start; remote mode: why the token could not be read. */
  error?: string | null;
  /** Daemon mode: the running sidecar is older than the daemon binary on disk (restart it). */
  note?: string | null;
  /** Remote mode: the paired daemon's display name. */
  name?: string | null;
}

/** The window's own frame buttons: the desktop app draws its title bar (window decorations are off). */
export interface HostWindow {
  minimize(): void;
  toggleMaximize(): void;
  close(): void;
}

export interface ResumableSession {
  id: string;
  modified: number;
  bytes: number;
  title: string;
  running: boolean;
}

export interface LocalProject {
  id: string;
  name: string;
  directory: string;
}

/** The desktop app's embedded runner (RUST-PERF-1): it runs the agent CLIs itself and speaks the daemon's frames. */
export interface EmbeddedRunner {
  transport(): TransportFactory;
  setProject(folder: string): Promise<LocalProject>;
  resumable(folder: string): Promise<ResumableSession[]>;
}

/** The phone's camera QR reader (pairing). */
export interface QrScanner {
  /** The code read; null when the user cancelled. */
  scan(): Promise<string | null>;
  cancel(): Promise<void>;
}

/** The daemon a request goes to: its socket URL and the window's token. */
export interface DaemonAt {
  url: string;
  token: string | null;
}

export interface Host {
  readonly kind: HostKind;
  /** The frame buttons; null where the platform draws them (a browser) or there is no window frame (a phone). */
  readonly window: HostWindow | null;
  /** The embedded runner, where the app has one (desktop). */
  readonly embedded: EmbeddedRunner | null;
  /** The camera QR reader, where there is one (phone). */
  readonly scanner: QrScanner | null;
  /** The app's own version ("0.2.0"); null in a browser. */
  version(): Promise<string | null>;
  /** The endpoint the app chose (sidecar, INFANTRY_URL, embedded runner, paired daemon); null = the page decides. */
  endpoint(): Promise<HostEndpoint | null>;
  /** The daemon link for [url]: from the app's own process (no browser Origin, the token as a bearer header) or the page's WebSocket. */
  transport(url: string, token: string | null): TransportFactory;
  /** One of the app's own commands (updates, pairing, runner choice); rejects where the app has none (a browser). */
  call<T>(command: string, args?: Record<string, unknown>): Promise<T>;
  /** One of the app's own events; resolves to its unsubscribe (none in a browser). */
  listen<T>(event: string, handler: (payload: T) => void): Promise<() => void>;
  /** A folder the user picks; null when cancelled or where there is no folder dialog. */
  pickFolder(title: string): Promise<string | null>;
  /**
   * WEBUI-1: one file the daemon serves over HTTP (a web UI plugin's bundle, `/ui-plugins/...`), read with this window's
   * credentials: the browser fetches it same-origin with the bearer; the apps read it from their own process, from the
   * daemon they are connected to and nothing else. Rejects with the status and the daemon's reason.
   */
  daemonGet(path: string, at: DaemonAt): Promise<ArrayBuffer>;
  /** Reloads the window (after pairing). */
  reload(): void;
}

/** `ws://host:port/ws` -> `http://host:port`: where the daemon serves its HTTP routes (`/avatars`, `/ui-plugins`). */
export function httpBase(wsUrl: string): string {
  return wsUrl.replace(/^ws/, "http").replace(/\/ws\/?$/, "");
}

/** The paths [Host.daemonGet] may read: the web UI bundles only. */
export const DAEMON_GET_PREFIXES: readonly string[] = ["/ui-plugins/"];

/** Null when [path] is one [Host.daemonGet] may read; else why not. */
export function daemonGetRefusal(path: string): string | null {
  if (!DAEMON_GET_PREFIXES.some((p) => path.startsWith(p))) return `${path}: only ${DAEMON_GET_PREFIXES.join(", ")} can be read`;
  if (/[?#\\]|\/\/|(^|\/)\.\.?(\/|$)/.test(path)) return `${path}: not a plain file path`;
  return null;
}

/** A plain browser: no frame buttons, no app commands, the page's WebSocket and fetch. */
export function browserHost(): Host {
  return {
    kind: "browser",
    window: null,
    embedded: null,
    scanner: null,
    version: async () => null,
    endpoint: async () => null,
    transport: (url, token) => browserTransport(url, token),
    call: (command) => Promise.reject(new Error(`${command}: not available in a browser`)),
    listen: async () => () => {},
    pickFolder: async () => null,
    async daemonGet(path, at) {
      const refused = daemonGetRefusal(path);
      if (refused) throw new Error(refused);
      const r = await fetch(`${httpBase(at.url)}${path}`, {
        headers: at.token ? { Authorization: `Bearer ${at.token}` } : {},
        credentials: "omit",
        cache: "no-cache",
      });
      if (!r.ok) throw new Error(`${path}: HTTP ${r.status}${r.statusText ? ` ${r.statusText}` : ""}: ${(await r.text()).slice(0, 200)}`);
      return r.arrayBuffer();
    },
    reload: () => location.reload(),
  };
}
