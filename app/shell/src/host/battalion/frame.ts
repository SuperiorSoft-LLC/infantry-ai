// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-17: this page as an app of BattalionOS drawn in a FRAME of the OS window (docs/commands.md of the OS, "The page in a frame").
// The page has no window of its own there, so it calls the OS by messages to the desktop that holds the frame: the same app commands
// (`os_window_info`, `os_app_status`, `os_app_health`, `os_close_app_window`, `os_badge`, `os_show_desktop`), the same results. The
// desktop knows which app this is from the frame that sent the message, never from the message. In a window of its own (the pop out
// later) the page keeps using the direct commands (`./index.ts`). No Tauri import here.
import { browserHost, type Host, type HostOs, type HostWindow, type LoginWindowHandlers } from "@infantry/client";
import { appIdOfLabel, badgeCount, endpointOf, type OsAppStatus } from "./os";

/** The query the OS adds to the page's url in a frame: `?bos-app=<app id>`. It only tells the page it is in the OS; the OS trusts the frame. */
export const FRAME_FLAG = "bos-app";

/**
 * The app id when this page is the page of an OS frame: it has a parent window and the flag names a valid app id. Being framed decides it,
 * and nothing else: on Windows wry puts the Tauri initialization script (`__TAURI_INTERNALS__`) into every sub-frame too (wry 0.57
 * `with_initialization_script_for_main_only`: "Windows: scripts are always added to subframes"), so the page in the OS frame sees Tauri
 * internals of the OS window and must not take them for its own window. What the page claims about itself never gives it rights; the OS
 * trusts the frame it made, not the message (docs/commands.md, "The page in a frame").
 */
export function frameAppId(g: { search?: string; framed?: boolean } = pageEnv()): string | null {
  if (!g.framed) return null;
  const v = new URLSearchParams(g.search ?? "").get(FRAME_FLAG);
  return appIdOfLabel(v ? `app-${v}` : null);
}

function pageEnv(): { search?: string; framed?: boolean } {
  if (typeof window === "undefined") return {};
  return { search: window.location.search, framed: window.parent !== window };
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

/**
 * BOS-27: the OS window has no Windows title bar, so the page's own bar carries the window buttons: each asks the OS by name (the OS
 * accepts them from the app view on screen only). `bar` tells the OS the page's bar is there, so it draws none over it.
 */
function windowOf(call: ReturnType<typeof frameCaller>): HostWindow {
  const ask = (cmd: string, args?: Record<string, unknown>) => void call<void>(cmd, args).catch((e) => console.warn(`[infantry] ${cmd}: ${message(e)}`));
  return {
    minimize: () => ask("os_window_minimize"),
    toggleMaximize: () => ask("os_window_toggle_maximize"),
    close: () => ask("os_window_close"),
    bar: (on) => ask("os_window_bar", { on }),
  };
}

/** A pointer press that starts a window drag: the left button on an element that carries `data-tauri-drag-region` itself (not on a child of it, so buttons and fields in the bar stay clickable). */
export function dragStart(e: { button: number; detail: number; target: unknown }): "drag" | "maximize" | null {
  if (e.button !== 0) return null;
  const t = e.target as { getAttribute?: (n: string) => string | null } | null;
  const v = t?.getAttribute?.("data-tauri-drag-region");
  if (v == null || v === "false") return null;
  return e.detail >= 2 ? "maximize" : "drag";
}

/**
 * The attribute does nothing in a frame (Tauri's script is the OS shell's, not the page's): the page starts the drag by message on a
 * press on a drag area, and a double click on it toggles maximize. Returns the unsubscribe.
 */
export function installFrameDrag(link: FrameLink, doc: Pick<Window, "addEventListener" | "removeEventListener"> = window): () => void {
  let n = 0;
  const on = (e: Event) => {
    const a = dragStart(e as MouseEvent);
    if (a) link.post({ bos: 1, id: `d${++n}`, cmd: a === "drag" ? "os_window_start_drag" : "os_window_toggle_maximize" });
  };
  doc.addEventListener("mousedown", on);
  return () => doc.removeEventListener("mousedown", on);
}

/** The Host for an OS frame: the page's own socket and fetch, no embedded runner; the window buttons ask the OS by name (BOS-27), which draws the taskbar and desktop. */
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
    window: windowOf(call),
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
