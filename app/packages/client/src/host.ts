// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// HC-1 (AUDIT-1b): what the window runs in, behind one interface. The app never calls a platform API itself: the desktop
// and phone apps (Tauri) implement [Host] in app/shell/src/host/tauri/, the only folder allowed to import `@tauri-apps`
// (test/host-guard.test.ts fails on any other import); a plain browser gets [browserHost]. The shell, the web UI plugins
// (WEBUI-1) and the shell-only app all go through it.
import { browserTransport, type TransportFactory } from "./connection";
import type { HostNotifier } from "./toasts";

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
  /** Hop timings are on (ui.json `debugHopTimings` or INFANTRY_UI_HOPS=1; `board` also opens the board): see hops.ts. */
  hops?: string | null;
  /** `embedded` | `daemon` | `connect` (desktop), `remote` (phone). */
  mode?: string;
  /** Daemon mode: why the sidecar did not start; remote mode: why the token could not be read. */
  error?: string | null;
  /** Daemon mode: the running sidecar is older than the daemon binary on disk (restart it). */
  note?: string | null;
  /** The desktop window's daemon socket: `direct` (the webview's own WebSocket, default) or `plugin` (INFANTRY_UI_TRANSPORT). */
  transport?: string | null;
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
  /** True when the scanner draws its own full screen (BattalionOS's Google scanner): the page shows no camera view or Cancel of its own. */
  readonly ownScreen?: boolean;
}

/** The daemon a request goes to: its socket URL and the window's token. */
export interface DaemonAt {
  url: string;
  token: string | null;
}

/** What the app hears from a login window it owns (the page cannot be framed, so it is a window of its own). */
export interface LoginWindowHandlers {
  /** The window moved to [url] (only addresses that start with a watched prefix are reported). */
  onUrl(url: string): void;
  /** The window was closed, by the user or by [LoginWindow.close]. */
  onClosed(): void;
}
export interface LoginWindow {
  close(): void;
}

/**
 * BOS-8: this window is one app of BattalionOS (the OS opened it as `app-<id>`). The OS owns updates, the desktop and the
 * window; the app asks it through these two calls. Absent in the standalone app and in a browser.
 */
export interface HostOs {
  /** The app's id in the OS ("infantry-ai"). */
  readonly appId: string;
  /** Back to the OS desktop: the OS brings it to the front and minimizes this window, which stays open; the app keeps running (`os_show_desktop`). */
  showDesktop(): Promise<void>;
  /** The agent counts the OS shows on this app's icon as one number, working + needing input (`os_badge {count}`; 0 clears). */
  badge(working: number, needsInput: number): Promise<void>;
}

export interface Host {
  /** BOS-8: set only inside a BattalionOS app window; null/absent elsewhere. */
  readonly os?: HostOs | null;
  readonly kind: HostKind;
  /** The frame buttons; null where the platform draws them (a browser) or there is no window frame (a phone). */
  readonly window: HostWindow | null;
  /** The embedded runner, where the app has one (desktop). */
  readonly embedded: EmbeddedRunner | null;
  /** The camera QR reader, where there is one (phone). */
  readonly scanner: QrScanner | null;
  /** PARITY-AUDIT-1 #5: OS notifications (the desktop app); absent = in-app toasts only. */
  readonly notify?: HostNotifier | null;
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
  /**
   * The one way to open a link outside the app: the system browser in the desktop app, a new tab in a browser or on the phone.
   * Nothing else calls `window.open` or relies on `target="_blank"` (test/open-external.test.ts).
   */
  openExternal(url: string): Promise<void>;
  /**
   * Opens [url] in a window the app owns and reports the addresses it reaches that start with one of [watch] (the desktop app
   * reads the navigation itself). A browser popup cannot be read across origins, and the phone has no second window: both
   * report nothing, so the caller keeps its paste field. Null = no window could be opened.
   */
  openLoginWindow(url: string, watch: readonly string[], handlers: LoginWindowHandlers): Promise<LoginWindow | null>;
}

/** A new tab of the page's own browser: the web and Android way to open a link. The only `window.open` of a link (test/open-external-guard.test.ts). */
export async function pageOpen(url: string): Promise<void> {
  window.open(url, "_blank", "noopener,noreferrer");
}

/**
 * [Host.openExternal] for an app: the system browser through [system] (the Tauri opener plugin) while [useSystem] says the window
 * is the desktop app, the page's own tab ([pageOpen]) otherwise (the phone, or no app at all).
 */
export function externalOpener(useSystem: () => boolean, system: (url: string) => Promise<void>, page: (url: string) => Promise<void> = pageOpen): (url: string) => Promise<void> {
  return (url) => (useSystem() ? system(url) : page(url));
}

/** The login page's own window features (a popup, not a tab). */
const POPUP_FEATURES = "popup=yes,width=520,height=760";
/** How often a popup is asked whether it was closed (a browser gives no close event for another origin). */
const POPUP_POLL_MS = 500;

/** A browser popup for [url]; null when blocked. Cross-origin, so it reports no addresses. */
function browserLoginWindow(url: string, handlers: LoginWindowHandlers): LoginWindow | null {
  const w = window.open(url, "infantry-login", POPUP_FEATURES);
  if (!w) return null;
  const t = setInterval(() => {
    if (w.closed) {
      clearInterval(t);
      handlers.onClosed();
    }
  }, POPUP_POLL_MS);
  return {
    close() {
      clearInterval(t);
      w.close();
    },
  };
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
    openExternal: pageOpen,
    openLoginWindow: async (url, _watch, handlers) => browserLoginWindow(url, handlers),
  };
}
