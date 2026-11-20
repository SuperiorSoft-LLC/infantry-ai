// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one pace store of this window, bound to the daemon store's link. The open Pace tab asks again every minute
// (the daemon samples every minute, so more often adds nothing).
import { useEffect } from "react";
import { useStore } from "zustand";
import { createPaceStore, type PaceActions, type PaceState, type PaceStore } from "@infantry/client";
import { daemonStore } from "@/daemon/daemon";

let store: PaceStore | null = null;

export function paceStore(): PaceStore {
  return (store ??= createPaceStore(daemonStore().getState()));
}

export function usePace<T>(select: (s: PaceState & PaceActions) => T): T {
  return useStore(paceStore(), select);
}

export const PACE_REFRESH_MS = 60_000;

/** Asks for the report now and each minute while the calling view is mounted. */
export function useKeepPaceFresh(): void {
  const refresh = usePace((s) => s.refresh);
  useEffect(() => {
    refresh();
    const id = setInterval(refresh, PACE_REFRESH_MS);
    return () => clearInterval(id);
  }, [refresh]);
}
