// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// "12s ago" since an agent's last activity, ticking live. One shared 1s clock drives every card (not a timer per card).
import { useSyncExternalStore } from "react";
import { ago } from "@/daemon/agents";

const listeners = new Set<() => void>();
let now = Date.now();
let timer: ReturnType<typeof setInterval> | undefined;
/** The clock ticks once a second while any card is mounted. */
const TICK_MS = 1000;

function subscribe(fn: () => void) {
  listeners.add(fn);
  if (!timer) {
    timer = setInterval(() => {
      now = Date.now();
      listeners.forEach((l) => l());
    }, TICK_MS);
  }
  return () => {
    listeners.delete(fn);
    if (listeners.size === 0 && timer) (clearInterval(timer), (timer = undefined));
  };
}

export function ActivityAgo({ at }: { at: number }) {
  const t = useSyncExternalStore(subscribe, () => now);
  return <>{ago(at, Math.max(t, at))} ago</>;
}
