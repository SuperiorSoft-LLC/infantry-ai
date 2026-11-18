// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one usage store of this window, bound to the daemon store's link, the hook components read it through, and a
// clock that ticks twice a minute so "ends in" and "updated Nm ago" stay true without a new frame.
import { useEffect, useState } from "react";
import { useStore } from "zustand";
import { createUsageStore, type UsageActions, type UsageState, type UsageStore } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let store: UsageStore | null = null;

export function usageStore(): UsageStore {
  return (store ??= createUsageStore(daemonStore().getState()));
}

export function useUsage<T>(select: (s: UsageState & UsageActions) => T): T {
  return useStore(usageStore(), select);
}

export function useNow(everyMs = 30_000): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(id);
  }, [everyMs]);
  return now;
}
