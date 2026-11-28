// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The toast stack (after the Kotlin ToastService / ToastHost): short notices bottom right, newest on top, each gone
// after its own time or when dismissed; the same text twice in a row bumps the count instead of stacking.
// toastOfFrame maps a daemon frame to a toast: an `error` answering a request whose id carries one of the UI's
// toast-owned prefixes (no inline view shows it), and a pushed bus notification.
import { createStore, type StoreApi } from "zustand/vanilla";
import type { Envelope } from "./wire";

export type ToastTone = "info" | "ok" | "warn" | "error";

export interface Toast {
  id: number;
  tone: ToastTone;
  text: string;
  detail?: string;
  count: number;
}

export interface ToastState {
  toasts: Toast[];
  push(t: { tone: ToastTone; text: string; detail?: string }, ms?: number): number;
  dismiss(id: number): void;
}

/** Default lifetimes; an error stays longer. */
export const TOAST_MS: Record<ToastTone, number> = { info: 5000, ok: 4000, warn: 7000, error: 9000 };
/** At most this many toasts show; the oldest goes first. */
export const TOASTS_SHOWN = 4;

/** Request-id prefixes whose refusals no inline view shows, so they toast. */
export const TOAST_OWNED = ["ui:", "agent-menu:", "start-next:"];

export function createToasts(timer: (fn: () => void, ms: number) => unknown = setTimeout): StoreApi<ToastState> {
  let next = 0;
  return createStore<ToastState>((set, get) => ({
    toasts: [],
    push(t, ms) {
      const top = get().toasts[0];
      if (top && top.text === t.text && top.tone === t.tone) {
        set({ toasts: [{ ...top, count: top.count + 1, detail: t.detail ?? top.detail }, ...get().toasts.slice(1)] });
        return top.id;
      }
      next += 1;
      const id = next;
      set({ toasts: [{ id, count: 1, ...t }, ...get().toasts].slice(0, TOASTS_SHOWN) });
      timer(() => get().dismiss(id), ms ?? TOAST_MS[t.tone]);
      return id;
    },
    dismiss(id) {
      set({ toasts: get().toasts.filter((x) => x.id !== id) });
    },
  }));
}

const TONES: Record<string, ToastTone> = { error: "error", warn: "warn", warning: "warn", success: "ok", ok: "ok" };

/** A device notification (`notify_push`, Kotlin NOTIFY-1) as this device shows it. */
export interface DevicePush {
  id: string;
  toast: { tone: ToastTone; text: string; detail?: string };
  /** A `data:` URL of the sound to play; absent when the daemon sent none (notify.* settings, quiet hours, size). */
  sound?: string;
}

const PUSH_TONES: Record<string, ToastTone> = { milestone: "ok", urgent: "error", info: "info" };

/**
 * The `notify_push` [env] for [device] ("desktop", "web", "android"), or null when it is not one or targets another
 * device. The daemon applies the notify.* settings (off, quiet hours, sound size) before it sends, so a push that
 * carries sound bytes plays them.
 */
export function devicePushOf(env: Envelope, device: string): DevicePush | null {
  if (env.type !== "notify_push") return null;
  try {
    const n = JSON.parse(env.data) as { id?: string; title?: string; body?: string; level?: string; target?: string; soundType?: string; soundData?: string };
    if (!n?.id || !n.title) return null;
    if (n.target && n.target !== "all" && n.target !== device) return null;
    const sound = n.soundData ? `data:${n.soundType || "audio/wav"};base64,${n.soundData}` : undefined;
    return { id: n.id, toast: { tone: PUSH_TONES[n.level ?? ""] ?? "info", text: n.title, detail: n.body || undefined }, sound };
  } catch {
    return null;
  }
}

/** The toast a daemon frame should raise, or null. */
export function toastOfFrame(env: Envelope): { tone: ToastTone; text: string; detail?: string } | null {
  if (env.type === "error") {
    const id = env.requestId ?? "";
    if (!TOAST_OWNED.some((p) => id.startsWith(p))) return null;
    const text = env.data.trim().split("\n")[0] || "The daemon refused the request";
    return { tone: "error", text };
  }
  if (env.type === "ticket") {
    // A refused start is an ok `ticket` frame whose `start` says why (not an `error` frame): toast it, naming the cause.
    if (!TOAST_OWNED.some((p) => (env.requestId ?? "").startsWith(p))) return null;
    try {
      const st = (JSON.parse(env.data) as { start?: { ok?: boolean; reason?: string; detail?: string } }).start;
      if (!st || st.ok !== false) return null;
      const text = (st.detail ?? "").trim().split("\n")[0] || `The ticket was not started (${st.reason ?? "refused"})`;
      return { tone: st.reason === "start_refused" ? "error" : "warn", text };
    } catch {
      return null;
    }
  }
  if (env.type === "notification_push") {
    try {
      const n = JSON.parse(env.data) as { title?: string; body?: string; level?: string };
      if (!n?.title) return null;
      return { tone: TONES[n.level ?? ""] ?? "info", text: n.title, detail: n.body };
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * PARITY-AUDIT-1 #5: a bus notice the daemon routed to the `os` channel (the `notification_push` frame lists the channels;
 * `notifications.osDesktop` off = it never lists `os`). [key] collapses repeats: the ticket, else the session, else the title.
 */
export interface OsNotice {
  key: string;
  title: string;
  body: string;
  ticketId?: string;
  sessionId?: string;
}

/** The host's OS notification seam (the desktop app); absent where there is none (browser, BattalionOS: toasts only). */
export interface HostNotifier {
  show(n: OsNotice): Promise<void>;
  /** The owner clicked the notice with [key] (the host already brought the window up). Returns the unsubscribe. */
  onClick(fn: (key: string) => void): () => void;
}

/** BOS-NOTIFY-1: the `tag` BattalionOS takes (up to 64 of `A-Za-z0-9_.:-`): a repeat with the same tag replaces the toast. */
export function bosTagOf(key: string): string {
  return key.replace(/[^A-Za-z0-9_.:-]/g, "_").slice(0, 64);
}

/**
 * BOS-NOTIFY-1: the OS notifier under BattalionOS: `os_notify {title, text, tag, route}` through the OS bridge [call] (a frame
 * message or `invoke`), the notice key as `route`. A refusal (an older BattalionOS without a desktop `os_notify`, the
 * per-app rate, no permission) is silent: the in-app toast (ToastHost) is shown for every notice anyway. [onEvent] hears
 * the OS's `os_notify_click` events (`{tag, route}`); a click hands the route (the key) back.
 */
export function bosNotifier(
  call: (cmd: string, args: Record<string, unknown>) => Promise<unknown>,
  onEvent: (fn: (e: { tag?: unknown; route?: unknown }) => void) => () => void,
): HostNotifier {
  return {
    show: (n) => call("os_notify", { title: n.title, text: n.body, tag: bosTagOf(n.key), route: n.key }).then(() => undefined, () => undefined),
    onClick: (fn) => onEvent((e) => void (typeof e.route === "string" && e.route ? fn(e.route) : undefined)),
  };
}

/** The OS notice of [env], or null when it is not a `notification_push` routed to `os`. */
export function osNoticeOf(env: Envelope): OsNotice | null {
  if (env.type !== "notification_push") return null;
  try {
    const n = JSON.parse(env.data) as { title?: string; body?: string; channels?: string[]; ticketId?: string; sessionId?: string };
    if (!n?.title || !n.channels?.includes("os")) return null;
    const key = n.ticketId ? `ticket:${n.ticketId}` : n.sessionId ? `session:${n.sessionId}` : `title:${n.title}`;
    return { key, title: n.title, body: n.body ?? "", ticketId: n.ticketId || undefined, sessionId: n.sessionId || undefined };
  } catch {
    return null;
  }
}

/** Repeats for the same ticket or session inside this window raise one OS notification. */
export const OS_COLLAPSE_MS = 60_000;

/**
 * Feeds frames to [show]: one OS notification per notice flagged for the OS, repeats of a key inside [windowMs]
 * collapsed. Returns the notice shown, or null. [notices] remembers the last shown per key (for the click).
 */
export function createOsNotifier(show: (n: OsNotice) => void, now: () => number = Date.now, windowMs: number = OS_COLLAPSE_MS) {
  const last = new Map<string, number>();
  const notices = new Map<string, OsNotice>();
  return {
    notices,
    frame(env: Envelope): OsNotice | null {
      const n = osNoticeOf(env);
      if (!n) return null;
      const t = now();
      const prev = last.get(n.key);
      if (prev !== undefined && t - prev < windowMs) return null;
      last.set(n.key, t);
      notices.set(n.key, n);
      for (const [k, at] of last) if (t - at >= windowMs) last.delete(k);
      show(n);
      return n;
    },
  };
}
