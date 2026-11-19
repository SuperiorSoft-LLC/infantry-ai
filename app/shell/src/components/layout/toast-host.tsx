// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The window's toast stack (@infantry/client createToasts), bottom right. It raises a toast for each daemon frame
// toastOfFrame maps (a refused UI request with no inline view, a pushed notification); `toast()` posts one from code.
// A device notification (`notify_push`) toasts, plays the sound the daemon sent (it applied the notify.* settings)
// and is acked for this device (`notify_ack` {id, device}).
import { useEffect } from "react";
import { useStore } from "zustand";
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from "lucide-react";
import { createToasts, toastOfFrame, type ToastTone, devicePushOf } from "@infantry/client";
import { cn } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";

/** This window's device name for `notify_push` targets and acks. */
const DEVICE = "desktop";

const toasts = createToasts();

/** Post a toast from UI code. */
export function toast(tone: ToastTone, text: string, detail?: string): void {
  toasts.getState().push({ tone, text, detail });
}

const ICON = { info: Info, ok: CheckCircle2, warn: AlertTriangle, error: XCircle } as const;
const TONE = { info: "border-info/50", ok: "border-ok/50", warn: "border-warn/60", error: "border-danger/60" } as const;
const ICON_TONE = { info: "text-info", ok: "text-ok", warn: "text-warn", error: "text-danger" } as const;

export function ToastHost() {
  const list = useStore(toasts, (s) => s.toasts);
  useEffect(
    () =>
      daemonStore().getState().onFrame((env) => {
        const t = toastOfFrame(env);
        if (t) toasts.getState().push(t);
        const p = devicePushOf(env, DEVICE);
        if (p) {
          toasts.getState().push(p.toast);
          if (p.sound) {
            try {
              void new Audio(p.sound).play().catch(() => undefined);
            } catch {
              // no audio device: the toast is enough
            }
          }
          daemonStore().getState().sendFrame({ type: "notify_ack", data: JSON.stringify({ id: p.id, device: DEVICE }) });
        }
      }),
    [],
  );
  if (!list.length) return null;
  return (
    <div className="pointer-events-none fixed right-4 bottom-10 z-50 flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2" role="status" aria-live="polite">
      {list.map((t) => {
        const Icon = ICON[t.tone];
        return (
          <div key={t.id} className={cn("pointer-events-auto flex items-start gap-2 rounded-lg border-2 bg-card p-2.5 text-[13px] shadow-lg", TONE[t.tone])}>
            <Icon className={cn("mt-0.5 size-4 shrink-0", ICON_TONE[t.tone])} />
            <div className="min-w-0 flex-1">
              <div className="break-words">
                {t.text}
                {t.count > 1 && <span className="ml-1 text-xs text-muted-foreground">×{t.count}</span>}
              </div>
              {t.detail && <div className="mt-0.5 line-clamp-3 text-xs text-muted-foreground">{t.detail}</div>}
            </div>
            <button type="button" aria-label="Dismiss" onClick={() => toasts.getState().dismiss(t.id)} className="text-muted-foreground hover:text-foreground">
              <X className="size-3.5" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
