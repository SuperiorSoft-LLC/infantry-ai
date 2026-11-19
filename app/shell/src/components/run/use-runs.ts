// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one runs store of this window (Run tab), bound to the daemon store's link.
import { useEffect } from "react";
import { useStore } from "zustand";
import { createRunsStore, type RunsActions, type RunsState, type RunsStore } from "@infantry/client";
import { daemonStore, useDaemon } from "@/daemon/daemon";

let store: RunsStore | null = null;

export function runsStore(): RunsStore {
  return (store ??= createRunsStore(daemonStore().getState()));
}

export function useRuns<T>(select: (s: RunsState & RunsActions) => T): T {
  return useStore(runsStore(), select);
}

/** Lists the saved commands and runs once the link is open. */
export function useRunsLoaded(): void {
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") runsStore().getState().refresh();
  }, [link]);
}
