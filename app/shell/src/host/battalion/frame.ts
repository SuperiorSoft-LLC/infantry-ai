// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-17: this page as an app of BattalionOS drawn in a FRAME of the OS window (docs/commands.md of the OS, "The page in a frame").
// The page has no window of its own there, so it calls the OS by messages to the desktop that holds the frame: the same app commands
// (`os_window_info`, `os_app_status`, `os_app_health`, `os_close_app_window`, `os_badge`, `os_show_desktop`), the same results. The
// desktop knows which app this is from the frame that sent the message, never from the message. In a window of its own (the pop out
// later) the page keeps using the direct commands (`./index.ts`). No Tauri import here.
import { browserHost, type Host, type HostOs, type LoginWindowHandlers } from "@infantry/client";
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

/** An event the desktop sends this frame (BOS-25: the sign-in window reached an address, or ended): `{bos: 1, event, ...}`, from the parent only. */
export type FrameEventHandler = (event: string, data: Record<string, unknown>) => void;

export function frameCaller(link: FrameLink, timeoutMs = 15_000, onEvent?: FrameEventHandler) {
  let n = 0;
  const pending = new Map<string, { ok: (v: unknown) => void; fail: (e: Error) => void }>();
  link.listen((data, source) => {
    const d = data as { bos?: unknown; id?: unknown; ok?: unknown; result?: unknown; error?: unknown } | null;
    if (source !== link.parent || !d || typeof d !== "object" || d.bos !== 1) return;
    if (typeof (d as { event?: unknown }).event === "string") return onEvent?.((d as { event: string }).event, d as Record<string, unknown>);
    if (typeof d.id !== "string") return;
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
  const loginHandlers = new Set<LoginWindowHandlers>();
  const call = frameCaller(link, undefined, (event, data) => {
    for (const h of [...loginHandlers]) {
      if (event === "os_login_url" && typeof data.url === "string") h.onUrl(data.url);
      else if (event === "os_login_closed") (loginHandlers.delete(h), h.onClosed());
    }
  });
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
    // BOS-25: the native things the standalone app does with its own window are asked of the OS by name (the OS checks the manifest permission of each).
    pickFolder: async (title) => (await call<string | null>("os_pick_folder", { title })) ?? null,
    openExternal: async (url) => void (await call<void>("os_open_external", { url })),
    async openLoginWindow(url, _watch, handlers) {
      // The OS opens the window (the page cannot), as the standalone app does; every address it reaches comes back, then its end.
      loginHandlers.add(handlers);
      try {
        await call<void>("os_login_window_open", { url });
      } catch (e) {
        loginHandlers.delete(handlers);
        throw new Error(message(e));
      }
      return {
        close() {
          loginHandlers.delete(handlers);
          void call<void>("os_login_window_close").catch(() => undefined);
        },
      };
    },
    // The app's own window commands (update, pairing, runner, CLI path, reports) have no meaning inside the OS: they never leave the page.
    // Only the OS's own app commands (`os_*`) go to the desktop.
    call: <T>(command: string, args?: Record<string, unknown>) =>
      command.startsWith("os_") ? call<T>(command, args) : Promise.reject<T>(new Error(`'${command}' is not available under BattalionOS`)),
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
