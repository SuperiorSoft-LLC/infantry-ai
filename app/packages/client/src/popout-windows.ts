// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WINDOW-MIN-1: a floating chat (ChatPopout) as a window of the BattalionOS window manager (OS-WINDOW-1, the vendored
// WM-5 client in ./bos-windows). Minimize sends it to the OS taskbar (one item per window) under BattalionOS, or to the
// in-app dock strip (PopoutDock) standalone and in a browser; a click on the item restores it. ChatPopout keeps its own
// box, so a restore comes back at the same size and place. The old fold-to-title-bar minimize stays as the "fold" mode.
import { createWindows, pickWindows, type Windows, type WindowHandle } from "./bos-windows/client";
import { LocalWindowManager } from "./bos-windows/local-backend";
import type { WindowBadge, WindowInfo, WindowState } from "./bos-windows/types.gen";

export type { Windows, WindowHandle, WindowInfo, WindowBadge };
export { LocalWindowManager, createWindows };

/** `taskbar` (default): minimize hides the window into the taskbar / dock. `fold`: the window folds to its title bar. */
export type MinimizeMode = "taskbar" | "fold";
/** The local preference (`localStorage`) that picks the mode: `ui.popouts.minimize`. */
export const MINIMIZE_MODE_KEY = "infantry.ui.popouts.minimize";

export function minimizeMode(storage: { getItem(k: string): string | null } | null = typeof localStorage !== "undefined" ? localStorage : null): MinimizeMode {
  try {
    return storage?.getItem(MINIMIZE_MODE_KEY) === "fold" ? "fold" : "taskbar";
  } catch {
    return "taskbar";
  }
}

export interface PopoutGeometry<B> {
  box: B;
  minimized: boolean;
  maximized: boolean;
}

/**
 * What the Minimize button does to the popout's own remembered state. Fold toggles the folded flag (and leaves maximize);
 * taskbar leaves the box and the maximized flag alone (the window is hidden by the window manager, not by this state),
 * so the restore brings it back as it was.
 */
export function minimizeGeometry<B>(g: PopoutGeometry<B>, mode: MinimizeMode): PopoutGeometry<B> {
  return mode === "fold" ? { ...g, minimized: !g.minimized, maximized: false } : g;
}

let picked: Promise<Windows> | null = null;
/** The one window manager of this page: the OS backend under BattalionOS, else a local one (no storage: ChatPopout keeps its own geometry). */
export function popoutWindows(): Promise<Windows> {
  return (picked ??= pickWindows({ storage: null, scope: "infantry.popouts" }));
}
/** Tests: start from a given window manager. */
export function setPopoutWindows(w: Windows | null) {
  picked = w ? Promise.resolve(w) : null;
}

const isMin = (s: WindowState) => s === "minimized";

export interface PopoutWindow {
  readonly handle: WindowHandle;
  minimize(): Promise<void>;
  restore(): Promise<void>;
  close(): Promise<void>;
  /** Title, badge and attention of the taskbar / dock item. */
  set(p: { title?: string; badge?: WindowBadge | null; attention?: boolean }): Promise<void>;
  /** Stop listening (the window stays open). */
  dispose(): void;
}

/** Opens (or finds, by key) the popout's window and tells [onMinimized] each time it goes into or out of the taskbar. */
export async function bindPopoutWindow(windows: Windows, spec: { key: string; title: string; icon?: string }, onMinimized: (minimized: boolean) => void): Promise<PopoutWindow> {
  const handle = await windows.open({ key: spec.key, kind: "inline", title: spec.title, icon: spec.icon, state: "normal" });
  let last = isMin(handle.state);
  const tell = (s: WindowState | undefined) => {
    if (s === undefined || isMin(s) === last) return;
    last = isMin(s);
    onMinimized(last);
  };
  // A window found open and minimized (the app reloaded) shows again: its taskbar item would otherwise be the only way back.
  if (last) {
    await handle.restore();
    last = false;
  }
  const offs = [handle.on("state", (e) => tell(e.state)), handle.on("focus", (e) => tell(e.state))];
  return {
    handle,
    async minimize() {
      tell(await handle.minimize());
    },
    async restore() {
      tell(await handle.focus());
    },
    async close() {
      offs.forEach((f) => f());
      if (!handle.closed) await handle.close();
    },
    async set(p) {
      await handle.set(p);
    },
    dispose() {
      offs.forEach((f) => f());
    },
  };
}

/** The items the in-app dock shows: the minimized windows, oldest first. */
export function dockItems(all: WindowInfo[]): WindowInfo[] {
  return all.filter((w) => isMin(w.state)).sort((a, b) => a.wid.localeCompare(b.wid, undefined, { numeric: true }));
}
