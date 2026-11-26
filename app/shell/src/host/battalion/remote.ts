// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// AND-10: Infantry AI as a page-only app of BattalionOS on a phone. No daemon runs there (the package has no lib/ and no bin/):
// the page talks to the daemon on the PC exactly as the standalone phone app does (PHONE-1, RUST-MOBILE-1): the same connect
// screen, the same infantry://connect link, the token as the socket's bearer. What the phone app's own process does
// (`remote_save`, `remote_forget`, `remote_check`, mobile.rs) is done here in the page, because the OS's page host offers no app
// commands beyond `os_*`. The pairing is kept in the page's own storage: the OS has no secrets store for apps yet (design note
// bos-android-design-2026-11-25 section 1, permission `secrets`), so this is the one place the token lives on the phone.
// Built only when the shell is built with VITE_INFANTRY_BOS_REMOTE=1 (scripts/bos-package.py --page-only); the Windows package
// keeps the frame host (./frame.ts) and its own daemon.
import { browserTransport, httpBase, socketUrl, type Host, type HostEndpoint, type QrScanner, type RemoteCheck } from "@infantry/client";
import { battalionFrameHost, frameCaller, type FrameLink } from "./frame";

/** Where the page keeps its pairing (page storage of the app's own origin). */
export const REMOTE_KEY = "infantry.bos.remote";

export interface SavedRemote {
  url: string;
  token: string | null;
  name: string | null;
}

/** The storage the page uses; a fake in tests. */
export interface KeyStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function loadRemote(s: KeyStore): SavedRemote | null {
  try {
    const v = JSON.parse(s.getItem(REMOTE_KEY) ?? "null") as Partial<SavedRemote> | null;
    if (!v || typeof v.url !== "string" || !v.url.trim()) return null;
    return { url: v.url, token: typeof v.token === "string" && v.token ? v.token : null, name: typeof v.name === "string" && v.name ? v.name : null };
  } catch {
    return null;
  }
}

export function saveRemote(s: KeyStore, r: SavedRemote): void {
  s.setItem(REMOTE_KEY, JSON.stringify({ url: socketUrl(r.url), token: r.token || null, name: r.name || null }));
}

/** The endpoint the daemon store opens: the saved PC, or an empty url (never paired: the connect screen opens). */
export function remoteEndpoint(saved: SavedRemote | null): HostEndpoint {
  return { url: saved?.url ?? "", token: saved?.token ?? null, stress: false, mode: "remote", name: saved?.name ?? null };
}

/** The probes [remoteCheck] runs; real network calls in the app, fakes in tests. */
export interface Probes {
  /** Resolves when the PC answers http at all (`/health`, any status), rejects when it cannot be reached. */
  health(base: string): Promise<void>;
  /** True when the daemon socket opens with the token, false when it is closed before opening. */
  socket(url: string, token: string | null): Promise<boolean>;
}

/**
 * The phone app's rule (remote_reach.rs `classify`) as far as a page can see it: no answer from `/health` = unreachable; an answer
 * but a socket that will not open with the saved token = refused (a page cannot read the 401 itself); both = ok.
 */
export async function remoteCheck(saved: SavedRemote | null, p: Probes): Promise<RemoteCheck> {
  if (!saved) return "unreachable";
  try {
    await p.health(httpBase(saved.url));
  } catch {
    return "unreachable";
  }
  return (await p.socket(saved.url, saved.token).catch(() => false)) ? "ok" : "refused";
}

/** How long one probe may take (the phone app's CHECK_SECS). */
const PROBE_MS = 5_000;

function pageProbes(): Probes {
  return {
    async health(base) {
      const c = new AbortController();
      const t = setTimeout(() => c.abort(), PROBE_MS);
      // no-cors: the daemon sends no CORS headers; an opaque answer still proves the PC answered, a network failure throws.
      try {
        await fetch(`${base}/health`, { mode: "no-cors", credentials: "omit", cache: "no-store", signal: c.signal });
      } finally {
        clearTimeout(t);
      }
    },
    socket: (url, token) =>
      new Promise<boolean>((resolve) => {
        const t = browserTransport(url, token)();
        const timer = setTimeout(() => (t.close(), resolve(false)), PROBE_MS);
        t.open({
          onOpen: () => (clearTimeout(timer), t.close(), resolve(true)),
          onText: () => undefined,
          onClose: () => (clearTimeout(timer), resolve(false)),
        });
      }),
  };
}

/** How long the page waits for the OS's scanner: the person is pointing the phone at the PC, so far longer than an ordinary call. */
export const SCAN_WAIT_MS = 10 * 60_000;

/**
 * PHONE-SCAN-1: the QR reader of the page-only app is BattalionOS's own (`os_scan_code`, manifest permission `scan.code`): the OS runs
 * Google's code scanner and hands the page only the text; the page never gets the camera. null = cancelled. A refusal is the OS's own
 * plain reason (no permission, scanner not available on this phone). Its own caller (ids `s1`, `s2`...) so the long wait never holds
 * the ordinary calls' timeout.
 */
export function osScanner(link: FrameLink, waitMs = SCAN_WAIT_MS): QrScanner {
  let call: ReturnType<typeof frameCaller> | null = null; // made on the first scan: no second listener on the link until then
  return {
    ownScreen: true,
    scan: async () => {
      call ??= frameCaller(link, waitMs, undefined, "s");
      const text = await call<unknown>("os_scan_code");
      return typeof text === "string" && text ? text : null;
    },
    // Google's scanner has its own Back and close; the page has nothing to cancel.
    cancel: async () => {},
  };
}

/**
 * The Host of the page-only app: the OS frame host (os_* commands by message) plus the phone app's pairing commands done in the
 * page. kind is `phone`, so the shell starts as the phone app does (connect screen when not paired or the PC does not answer).
 */
export function battalionRemoteHost(appId: string, link: FrameLink, store: KeyStore = localStorage, probes: Probes = pageProbes()): Host {
  const frame = battalionFrameHost(appId, link);
  const pairing: Record<string, (args?: Record<string, unknown>) => Promise<unknown>> = {
    remote_save: async (a) => {
      const url = typeof a?.url === "string" ? a.url : "";
      if (!url.trim()) throw new Error("the pairing carries no daemon address");
      saveRemote(store, { url, token: typeof a?.token === "string" ? a.token : null, name: typeof a?.name === "string" ? a.name : null });
      return socketUrl(url);
    },
    remote_forget: async () => store.removeItem(REMOTE_KEY),
    remote_check: () => remoteCheck(loadRemote(store), probes),
  };
  return {
    ...frame,
    kind: "phone",
    scanner: osScanner(link),
    endpoint: async () => remoteEndpoint(loadRemote(store)),
    transport: (url, token) => browserTransport(url, token),
    call: <T>(command: string, args?: Record<string, unknown>) => (pairing[command] ? (pairing[command](args) as Promise<T>) : frame.call<T>(command, args)),
  };
}
