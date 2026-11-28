// SPDX-License-Identifier: MPL-2.0
// VENDORED for the MIN-TASKBAR-1 contract test from BattalionOS origin/battalion-os @ babe8ec, crates/battalion-desktop/ui/src/frames.ts. Do not edit: copy again.
// The bridge between an app's page (in a frame the shell made) and the OS (docs/commands.md, "The page in a frame"). Pure: no DOM, so
// identity and the allow list are tested without a window.
//
// The page sends `{bos: 1, id: <request id>, cmd, args?}` to its parent. The shell never believes the message about WHO sent it:
// the app is the frame whose window is `event.source`, and the message must come from that frame's own origin. A message from a
// window the shell did not make gets no answer at all. Only today's app commands are served; the rest are refused.

import { osThemeEvent, type OsThemeEvent, type WinTokens } from "../kit/lib/win-tokens";

/** WM-2 (OS-WINDOW-1): the window calls. The host checks the `window` permission and that a wid is the app's own. */
export const WIN_COMMANDS = ["os_win_open", "os_win_close", "os_win_minimize", "os_win_restore", "os_win_maximize", "os_win_focus", "os_win_snap", "os_win_set", "os_win_list"] as const;
export type WinCommand = (typeof WIN_COMMANDS)[number];

/** The app commands a page may call from a frame (the `app-*` capability of docs/commands.md), same names and results. */
export const FRAME_COMMANDS = ["os_window_info", "os_app_status", "os_app_health", "os_close_app_window", "os_badge", "os_show_desktop", "os_pick_folder", "os_pick_file", "os_open_external", "os_login_window_open", "os_login_window_close", "os_notify", "os_scan_code", ...WIN_COMMANDS] as const;
export type FrameCommand = (typeof FRAME_COMMANDS)[number];

/** A frame the shell made: the app it shows, the origin its page has, and its window (null once the frame is gone). `wid` (WM-6):
 *  the frame is the content of native window `wid` (its host page knows it from the window the host made it for). */
export interface FrameEntry { id: string; origin: string; window: unknown; wid?: string }

/** What the shell does for the app of a frame. The app id is always the frame's. */
export interface FrameBackend {
  appStatus(id: string): Promise<unknown>;
  appHealth(id: string): Promise<unknown>;
  badge(id: string, count: number | undefined, text: string | undefined): Promise<void>;
  /** Close = the view ends; the app and its agents go on. */
  close(id: string): void;
  /** Minimize = back to the desktop; the view keeps its state. */
  minimize(id: string): void;
  /** BOS-25: the native calls. The host checks the manifest permission of each (`files.user`, `network`, `login.window`). */
  pickFolder(id: string, title: string | undefined): Promise<string | null>;
  pickFile(id: string, title: string | undefined): Promise<string | null>;
  openExternal(id: string, url: string): Promise<void>;
  loginOpen(id: string, url: string): Promise<void>;
  loginClose(id: string): Promise<void>;
  /** AND-4: a notice on the app's own channel (`notifications`) and a QR code scanned by the OS (`scan.code`). */
  notify(id: string, title: string, text: string | undefined): Promise<void>;
  scanCode(id: string): Promise<string | null>;
  /** WM-2: a window call for the app of the frame (`os_frame_win_*`); `args` are the page's. */
  win(id: string, cmd: WinCommand, args: Record<string, unknown>): Promise<unknown>;
  /** AND-4: the reason a command outside the list is refused, when the device has its own (a phone: starting a program). */
  refusal?(id: string, cmd: string): Promise<string | undefined>;
}

export interface FrameEvent { source: unknown; origin: string; data: unknown }
export type FrameReply = { to: unknown; origin: string; message: { bos: 1; id: string; ok: true; result: unknown } | { bos: 1; id: string; ok: false; error: string } };

const REQUEST_ID = /^[A-Za-z0-9_.:-]{1,64}$/;
const text = (e: unknown) => (typeof e === "string" ? e : e instanceof Error ? e.message : JSON.stringify(e));

/** The answer to one message, or null when the message gets nothing (a window the shell did not make, a frame that left its origin, not a request). */
export async function answer(frames: readonly FrameEntry[], ev: FrameEvent, be: FrameBackend): Promise<FrameReply | null> {
  const frame = ev.source == null ? undefined : frames.find((f) => f.window != null && f.window === ev.source);
  if (!frame || ev.origin !== frame.origin) return null;
  const d = ev.data as { bos?: unknown; id?: unknown; cmd?: unknown; args?: unknown } | null;
  if (!d || typeof d !== "object" || d.bos !== 1 || typeof d.id !== "string" || !REQUEST_ID.test(d.id) || typeof d.cmd !== "string") return null;
  const reply = (r: { ok: true; result: unknown } | { ok: false; error: string }): FrameReply => ({ to: frame.window, origin: frame.origin, message: { bos: 1, id: d.id as string, ...r } });
  if (!(FRAME_COMMANDS as readonly string[]).includes(d.cmd)) {
    const why = be.refusal ? await be.refusal(frame.id, d.cmd).catch((e) => text(e)) : undefined;
    return reply({ ok: false, error: why ?? `'${d.cmd}' is not available to an app's page` });
  }
  const args = d.args && typeof d.args === "object" ? (d.args as Record<string, unknown>) : {};
  // Today's app commands name no app or only their own; a page can never act for another one.
  if (args.id !== undefined && args.id !== frame.id) return reply({ ok: false, error: `app '${frame.id}' may not act on app '${String(args.id)}'` });
  try {
    switch (d.cmd as FrameCommand) {
      case "os_window_info":
        return reply({ ok: true, result: frame.wid ? { label: `win-${frame.wid.slice(2)}`, app: frame.id, wid: frame.wid } : { label: `app-${frame.id}`, app: frame.id } });
      case "os_app_status":
        return reply({ ok: true, result: await be.appStatus(frame.id) });
      case "os_app_health":
        return reply({ ok: true, result: await be.appHealth(frame.id) });
      case "os_badge": {
        const count = typeof args.count === "number" ? args.count : undefined;
        const t = typeof args.text === "string" ? args.text : undefined;
        await be.badge(frame.id, count, t);
        return reply({ ok: true, result: null });
      }
      case "os_close_app_window":
        be.close(frame.id);
        return reply({ ok: true, result: null });
      case "os_show_desktop":
        be.minimize(frame.id);
        return reply({ ok: true, result: null });
      case "os_pick_folder":
        return reply({ ok: true, result: await be.pickFolder(frame.id, typeof args.title === "string" ? args.title : undefined) });
      case "os_pick_file":
        return reply({ ok: true, result: await be.pickFile(frame.id, typeof args.title === "string" ? args.title : undefined) });
      case "os_open_external":
        if (typeof args.url !== "string") return reply({ ok: false, error: "os_open_external needs a url" });
        await be.openExternal(frame.id, args.url);
        return reply({ ok: true, result: null });
      case "os_login_window_open":
        if (typeof args.url !== "string") return reply({ ok: false, error: "os_login_window_open needs a url" });
        await be.loginOpen(frame.id, args.url);
        return reply({ ok: true, result: null });
      case "os_login_window_close":
        await be.loginClose(frame.id);
        return reply({ ok: true, result: null });
      case "os_notify":
        if (typeof args.title !== "string") return reply({ ok: false, error: "os_notify needs a title" });
        await be.notify(frame.id, args.title, typeof args.text === "string" ? args.text : undefined);
        return reply({ ok: true, result: null });
      case "os_scan_code":
        return reply({ ok: true, result: await be.scanCode(frame.id) });
      case "os_win_open":
      case "os_win_close":
      case "os_win_minimize":
      case "os_win_restore":
      case "os_win_maximize":
      case "os_win_focus":
      case "os_win_snap":
      case "os_win_set":
      case "os_win_list":
        return reply({ ok: true, result: (await be.win(frame.id, d.cmd as WinCommand, args)) ?? null });
    }
  } catch (e) {
    return reply({ ok: false, error: text(e) });
  }
}

/** BOS-25: an event for ONE app's frame (the sign-in window of that app reached an address, or ended). Sent to that frame only, at its origin. */
export function eventFor(frames: readonly FrameEntry[], appId: string, event: "os_login_url" | "os_login_closed", data?: { url: string }): { to: unknown; origin: string; message: Record<string, unknown> } | null {
  const frame = frames.find((f) => f.id === appId && f.window != null);
  return frame ? { to: frame.window, origin: frame.origin, message: { bos: 1, event, ...(data ?? {}) } } : null;
}

/** WM-2: the host's `os://win` event (`{bos:1, event:"os_win", id, wid, change, ...}`) for the frame of app `id` only, without `id`. */
export function winEventFor(frames: readonly FrameEntry[], payload: unknown): { to: unknown; origin: string; message: Record<string, unknown> } | null {
  const p = payload as Record<string, unknown> | null;
  if (!p || typeof p !== "object" || p.event !== "os_win" || typeof p.id !== "string" || typeof p.wid !== "string") return null;
  const { id, ...rest } = p;
  const frame = frames.find((f) => f.id === id && f.window != null);
  return frame ? { to: frame.window, origin: frame.origin, message: { ...rest, bos: 1, event: "os_win" } } : null;
}

/** WM-4: the `os_theme` event for every app frame that has a window (sent at start, on a frame load and on a light/dark change). */
export function themeEvents(frames: readonly FrameEntry[], tokens: WinTokens): { id: string; to: unknown; origin: string; message: OsThemeEvent }[] {
  return frames.filter((f) => f.window != null).map((f) => ({ id: f.id, to: f.window, origin: f.origin, message: osThemeEvent(tokens) }));
}
