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
