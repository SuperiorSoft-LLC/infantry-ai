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

/** The toast a daemon frame should raise, or null. */
export function toastOfFrame(env: Envelope): { tone: ToastTone; text: string; detail?: string } | null {
  if (env.type === "error") {
    const id = env.requestId ?? "";
    if (!TOAST_OWNED.some((p) => id.startsWith(p))) return null;
    const text = env.data.trim().split("\n")[0] || "The daemon refused the request";
    return { tone: "error", text };
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
