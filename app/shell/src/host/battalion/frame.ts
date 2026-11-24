// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-17: this page as an app of BattalionOS drawn in a FRAME of the OS window (docs/commands.md of the OS, "The page in a frame").
// The page has no window of its own there, so it calls the OS by messages to the desktop that holds the frame: the same app commands
// (`os_window_info`, `os_app_status`, `os_app_health`, `os_close_app_window`, `os_badge`, `os_show_desktop`), the same results. The
// desktop knows which app this is from the frame that sent the message, never from the message. In a window of its own (the pop out
// later) the page keeps using the direct commands (`./index.ts`). No Tauri import here.
import { browserHost, type Host, type HostOs } from "@infantry/client";
import { appIdOfLabel, badgeCount, endpointOf, type OsAppStatus } from "./os";

/** The query the OS adds to the page's url in a frame: `?bos-app=<app id>`. It only tells the page it is in the OS; the OS trusts the frame. */
export const FRAME_FLAG = "bos-app";

/** The app id when this page is the page of an OS frame: it has a parent window, the flag names a valid app id, and it is not a Tauri window. */
export function frameAppId(g: { search?: string; framed?: boolean; tauri?: boolean } = pageEnv()): string | null {
  if (!g.framed || g.tauri) return null;
  const v = new URLSearchParams(g.search ?? "").get(FRAME_FLAG);
  return appIdOfLabel(v ? `app-${v}` : null);
}

function pageEnv(): { search?: string; framed?: boolean; tauri?: boolean } {
  if (typeof window === "undefined") return {};
  return { search: window.location.search, framed: window.parent !== window, tauri: "__TAURI_INTERNALS__" in window };
}

/** One request to the desktop and its answer (matched by the request id; only the parent window may answer). */
export interface FrameLink {
  post(message: unknown): void;
  /** Called with every message the page receives and the window it came from; returns the unsubscribe. */
  listen(cb: (data: unknown, source: unknown) => void): () => void;
  /** The parent window, the only source whose answers count. */
  parent: unknown;
}

const message = (e: unknown) => (e instanceof Error ? e.message : typeof e === "string" ? e : (JSON.stringify(e) ?? String(e)));

export function frameCaller(link: FrameLink, timeoutMs = 15_000) {
  let n = 0;
  const pending = new Map<string, { ok: (v: unknown) => void; fail: (e: Error) => void }>();
  link.listen((data, source) => {
    const d = data as { bos?: unknown; id?: unknown; ok?: unknown; result?: unknown; error?: unknown } | null;
    if (source !== link.parent || !d || typeof d !== "object" || d.bos !== 1 || typeof d.id !== "string") return;
    const p = pending.get(d.id);
    if (!p) return;
    pending.delete(d.id);
    if (d.ok === true) p.ok(d.result);
    else p.fail(new Error(typeof d.error === "string" ? d.error : "the desktop refused"));
  });
  return <T>(cmd: string, args?: Record<string, unknown>): Promise<T> =>
    new Promise<T>((resolve, reject) => {
      const id = `r${++n}`;
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`${cmd}: the desktop did not answer`));
      }, timeoutMs);
      pending.set(id, { ok: (v) => (clearTimeout(timer), resolve(v as T)), fail: (e) => (clearTimeout(timer), reject(e)) });
      link.post({ bos: 1, id, cmd, args });
    });
}

let badgeWarned = false;

function osOf(appId: string, call: ReturnType<typeof frameCaller>): HostOs {
  return {
    appId,
    // Desktop = Minimize: the OS shows its desktop; this view keeps its state and the app keeps running.
    showDesktop: () => call<void>("os_show_desktop").catch((e) => Promise.reject(new Error(message(e)))),
    badge: (working, needsInput) =>
      call<void>("os_badge", { count: badgeCount(working, needsInput) }).catch((e) => {
        if (!badgeWarned) (badgeWarned = true), console.warn(`[infantry] os_badge: ${message(e)}`);
      }),
  };
}

/** The Host for an OS frame: the page's own socket and fetch, no embedded runner, no frame buttons (the OS draws the window, taskbar and desktop). */
export function battalionFrameHost(appId: string, link: FrameLink): Host {
  const call = frameCaller(link);
  const page = browserHost();
  return {
    ...page,
    kind: "desktop",
    os: osOf(appId, call),
    version: async () => null,
    async endpoint() {
      const st = await call<OsAppStatus>("os_app_status").catch((e) => Promise.reject(new Error(message(e))));
      return endpointOf(st);
    },
    call: <T>(command: string, args?: Record<string, unknown>) => call<T>(command, args),
    listen: async () => () => {},
  };
}

/** The link to the desktop of this window: messages to `window.parent`. */
export function windowLink(w: Window = window): FrameLink {
  return {
    post: (m) => w.parent.postMessage(m, "*"),
    listen: (cb) => {
      const on = (e: MessageEvent) => cb(e.data, e.source);
      w.addEventListener("message", on);
      return () => w.removeEventListener("message", on);
    },
    parent: w.parent,
  };
}
