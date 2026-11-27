// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// VENDORED from BattalionOS battalion-os @ 957c38f (WM-5), crates/battalion-desktop/ui/kit/lib. Do not edit here: change it in BattalionOS and copy again.
// WM-5 (OS-WINDOW-1 §2.4): the one interface both window backends speak. It is the `os_win_*` call surface itself
// (docs/commands.md, "Window calls (WM-2)"), so the OS backend is a thin transport and the local backend is the same
// rules run in the page.
import type { WinEvent } from "./types.gen";

export const WIN_CALLS = ["os_win_open", "os_win_close", "os_win_minimize", "os_win_restore", "os_win_maximize", "os_win_focus", "os_win_snap", "os_win_set", "os_win_list"] as const;
export type WinCall = (typeof WIN_CALLS)[number];

export interface WindowsBackend {
  /** `os`: BattalionOS carries the calls out. `local`: an in-page window manager (standalone Tauri, the browser). */
  readonly name: "os" | "local";
  call(cmd: WinCall, args?: Record<string, unknown>): Promise<unknown>;
  /** Every `os_win` event for this app's windows. Returns the unsubscribe. */
  onEvent(fn: (e: WinEvent) => void): () => void;
}

/** A `{bos: 1, event: "os_win", ...}` message as a WinEvent, or null when it is not one. */
export function parseWinEvent(m: unknown): WinEvent | null {
  if (!m || typeof m !== "object") return null;
  const { bos, event, id: _app, ...rest } = m as Record<string, unknown>;
  if (bos !== 1 || event !== "os_win" || typeof rest.wid !== "string") return null;
  return rest.change === "state" || rest.change === "closed" || rest.change === "focus" ? (rest as unknown as WinEvent) : null;
}
