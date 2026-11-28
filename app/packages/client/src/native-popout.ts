// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// POPOUT-1 (OS-WINDOW-1 §5 row 9): "Open in its own window" for a session. Only under BattalionOS: the OS window manager opens a
// native window (`os_win_open {kind: "native"}`) whose frame loads this page again with `popout=session:<id>`, and the shell then
// draws that one chat with no sidebar or headers. The key `session.<id>` makes a second click focus the open window. Size,
// position, restore-on-start and memory are the OS's (WM-6, WM-7). Standalone, in a browser and on a phone the option is hidden;
// the in-app floating chat (the ↗ button) is unchanged and stays the cheap default.
import type { Windows, WindowHandle } from "./bos-windows/client";

/** The query parameter that puts the page in pop-out mode. */
export const POPOUT_PARAM = "popout";

export type PopoutView = { kind: "session"; id: string };

/** The view this page shows alone, or null for the full layout. */
export function popoutViewOf(search: string): PopoutView | null {
  const v = new URLSearchParams(search).get(POPOUT_PARAM);
  const m = v?.match(/^session:([A-Za-z0-9._-]{1,128})$/);
  return m ? { kind: "session", id: m[1] } : null;
}

/** True when the page's window manager can open native windows (BattalionOS, not a phone, not inside a pop-out). */
export function nativePopoutAvailable(ws: Windows | null, opts: { mobile?: boolean; inPopout?: boolean } = {}): boolean {
  return !!ws && ws.backend.name === "os" && !opts.mobile && !opts.inPopout;
}

/** The route of a session's pop-out: this page's own path, its OS flag kept, plus `popout=session:<id>`. */
export function sessionPopoutRoute(pathname: string, search: string, sessionId: string): string {
  const q = new URLSearchParams(search);
  q.delete(POPOUT_PARAM);
  q.set(POPOUT_PARAM, `session:${sessionId}`);
  const path = pathname.startsWith("/") && !pathname.startsWith("//") ? pathname : "/";
  return `${path}?${q.toString()}`;
}

export const sessionPopoutKey = (sessionId: string) => `session.${sessionId}`;

/** Opens the session's native window, or focuses it when it is already open (the OS answers the same key with that window). */
export async function openSessionPopout(ws: Windows, spec: { sessionId: string; title: string; pathname: string; search: string }): Promise<WindowHandle> {
  const h = await ws.open({ key: sessionPopoutKey(spec.sessionId), kind: "native", title: spec.title, route: sessionPopoutRoute(spec.pathname, spec.search, spec.sessionId) });
  if (h.state === "minimized") await h.restore();
  else await h.focus().catch(() => h.state);
  return h;
}

/**
 * FLOAT-ADOPT-1 (WM-8): the first pop-out of a session under BattalionOS. The OS draws a float window in this same webview
 * (`os_win_open {kind: "float"}`) with its own chrome: minimize to the BOS taskbar, maximize, snap, and "Pop out to its own
 * window" (native). Same key and route as the native window, so the OS can move it from one kind to the other. Calls
 * [onClosed] once when the OS closes it. Rejects (or never answers) on a BattalionOS without os_win_*: the caller falls back.
 */
export async function openSessionFloat(
  ws: Windows,
  spec: { sessionId: string; title: string; pathname: string; search: string; bounds?: { x: number; y: number; w: number; h: number } },
  onClosed: () => void,
): Promise<WindowHandle> {
  const h = await ws.open({
    key: sessionPopoutKey(spec.sessionId),
    kind: "float",
    title: spec.title,
    route: sessionPopoutRoute(spec.pathname, spec.search, spec.sessionId),
    ...(spec.bounds ? { bounds: spec.bounds } : {}),
  });
  if (h.closed) onClosed();
  else h.on("closed", onClosed);
  if (h.state === "minimized") await h.restore();
  return h;
}
