// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-8: this window as an app of BattalionOS (docs/commands.md of the OS: windows `app-<id>`, commands `os_*`). The window
// is the OS's own Tauri window for the app; the page is served by the app's daemon, so its WebSocket is the page's own.
// With the standalone host, this folder is the only one that imports `@tauri-apps` (packages/client/test/tauri-import-guard.test.ts).
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { browserHost, createWindows, osBackend, osTauriTransport, setPopoutWindows, type Host, type HostOs } from "@infantry/client";
import { appIdOfLabel, badgeCount, endpointOf, type OsAppStatus } from "./os";

export { appIdOfLabel, endpointOf } from "./os";

type Internals = { metadata?: { currentWindow?: { label?: string } } };

/** The Tauri window's label, read from the runtime (the OS gives it; no argument can fake it). Null outside Tauri. */
export function windowLabel(g: Record<string, unknown> = globalThis as Record<string, unknown>): string | null {
  const i = g["__TAURI_INTERNALS__"] as Internals | undefined;
  return i?.metadata?.currentWindow?.label ?? null;
}

/** The app id when this window is an OS app window (`app-<id>`), else null. */
export const battalionAppId = (g?: Record<string, unknown>): string | null => appIdOfLabel(windowLabel(g));

const message = (e: unknown) => (e instanceof Error ? e.message : typeof e === "string" ? e : (JSON.stringify(e) ?? String(e)));
let badgeWarned = false;

function osOf(appId: string): HostOs {
  return {
    appId,
    // The OS knows which app this is from the window (no id is sent): it brings its desktop to the front and minimizes this window.
    showDesktop: () => invoke<void>("os_show_desktop").catch((e) => Promise.reject(new Error(message(e)))),
    // A failed badge is shown once in the console, never thrown into the UI (it runs on every count change).
    badge: (working, needsInput) =>
      invoke<void>("os_badge", { count: badgeCount(working, needsInput) }).catch((e) => {
        if (!badgeWarned) (badgeWarned = true), console.warn(`[infantry] os_badge: ${message(e)}`);
      }),
  };
}

/**
 * MIN-TASKBAR-1: the page of an `app-<id>` window is not in a frame, so `pickWindows` would pick the in-page manager (the
 * in-app dock). Its window calls go to the OS by `invoke` (os_win_*, capability `app-windows`) and its events come on `os://win`.
 */
export function bindOsWindows(): void {
  setPopoutWindows(
    createWindows(
      osBackend(
        osTauriTransport(
          (cmd, args) => invoke(cmd, args),
          (event, fn) => {
            let off: (() => void) | null = null;
            let gone = false;
            void Promise.resolve(listen(event, fn))
              .then((u) => (gone ? u?.() : (off = u ?? null)))
              .catch(() => {});
            return () => ((gone = true), off?.());
          },
        ),
      ),
    ),
  );
}

/** The Host for an OS app window: the page's own socket and fetch, no embedded runner, no frame (the OS draws the window). */
export function battalionHost(appId: string): Host {
  const page = browserHost();
  bindOsWindows();
  return {
    ...page,
    kind: "desktop",
    os: osOf(appId),
    version: async () => null,
    async endpoint() {
      const st = await invoke<OsAppStatus>("os_app_status", { id: appId }).catch((e) => Promise.reject(new Error(message(e))));
      return endpointOf(st);
    },
    call: <T>(command: string, args?: Record<string, unknown>) => invoke<T>(command, args),
    listen: <T>(event: string, handler: (payload: T) => void) => listen<T>(event, (e) => handler(e.payload)),
  };
}
